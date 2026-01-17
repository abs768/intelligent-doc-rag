package com.chatassistant.aichatassistant.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

@Service
public class QdrantService {

    private static final Logger logger = LoggerFactory.getLogger(QdrantService.class);

    @Value("${qdrant.host}")
    private String host;

    @Value("${qdrant.port}")
    private int port;

    @Value("${qdrant.collection}")
    private String collectionName;

    private QdrantClient client;

    // Keep this aligned with your embedding model
    private static final int VECTOR_SIZE = 768;

    @PostConstruct
    public void init() {
        try {
            logger.info("Connecting to Qdrant gRPC at {}:{}", host, port);
            client = new QdrantClient(QdrantGrpcClient.newBuilder(host, port, false).build());
            ensureCollection();
            logger.info("Qdrant ready. Collection='{}'", collectionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Qdrant client", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (client != null) client.close();
        } catch (Exception ignored) {}
    }

    private void ensureCollection() {
        try {
            boolean exists = client.collectionExistsAsync(collectionName).get();
            if (exists) return;

            client.createCollectionAsync(
                    collectionName,
                    Collections.VectorParams.newBuilder()
                            .setSize(VECTOR_SIZE)
                            .setDistance(Collections.Distance.Cosine)
                            .build()
            ).get();

        } catch (Exception e) {
            throw new RuntimeException("Failed to create/verify Qdrant collection: " + collectionName, e);
        }
    }

    // ---------------- UPSERT ----------------

    public void upsertChunk(
            UUID userId,
            UUID documentId,
            int chunkIndex,
            List<Float> vector,
            String content,
            String filename
    ) {
        if (userId == null || documentId == null) throw new IllegalArgumentException("userId/documentId required");
        if (vector == null || vector.isEmpty()) throw new IllegalArgumentException("Vector must not be empty");
        if (vector.size() != VECTOR_SIZE) {
            throw new IllegalArgumentException("Vector dimension mismatch: expected " + VECTOR_SIZE + ", got " + vector.size());
        }

        try {
            // Stable, collision-proof within a document
            String idString = documentId + ":" + chunkIndex;
            UUID pointId = UUID.nameUUIDFromBytes(idString.getBytes(StandardCharsets.UTF_8));

            Points.PointStruct point = Points.PointStruct.newBuilder()
                    .setId(id(pointId))
                    .setVectors(vectors(vector))
                    .putAllPayload(Map.of(
                            "userId", value(userId.toString()),
                            "documentId", value(documentId.toString()),
                            "chunkIndex", value(chunkIndex),
                            "filename", value(filename == null ? "" : filename),
                            "content", value(content == null ? "" : content)
                    ))
                    .build();

            client.upsertAsync(collectionName, List.of(point)).get();
        } catch (Exception e) {
            throw new RuntimeException("Qdrant upsert failed for documentId=" + documentId, e);
        }
    }

    // ---------------- SEARCH ----------------

    public List<String> search(
            UUID userId,
            List<Float> queryVector,
            int limit,
            List<UUID> documentIds
    ) {
        if (userId == null) return List.of();
        if (queryVector == null || queryVector.isEmpty()) return List.of();
        if (queryVector.size() != VECTOR_SIZE) {
            throw new IllegalArgumentException("Query vector dimension mismatch: expected " + VECTOR_SIZE + ", got " + queryVector.size());
        }
        if (limit <= 0) return List.of();

        try {
            Points.SearchPoints.Builder builder = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryVector)
                    .setLimit(limit)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setFilter(buildUserScopedFilter(userId, documentIds));

            List<Points.ScoredPoint> results = client.searchAsync(builder.build()).get();

            return results.stream()
                    .map(p -> p.getPayloadMap().get("content"))
                    .filter(Objects::nonNull)
                    .map(v -> v.getStringValue())
                    .filter(s -> s != null && !s.isBlank())
                    .toList();

        } catch (Exception e) {
            throw new RuntimeException("Qdrant search failed", e);
        }
    }

    // ---------------- DELETE ----------------

    public void deleteDocument(UUID userId, UUID documentId) {
        if (userId == null || documentId == null) return;

        try {
            Points.Filter filter = buildUserAndDocumentFilter(userId, documentId);
            List<Points.RetrievedPoint> points = scrollAll(filter);

            if (points.isEmpty()) return;

            List<Points.PointId> ids = points.stream()
                    .map(Points.RetrievedPoint::getId)
                    .collect(Collectors.toList());

            client.deleteAsync(collectionName, ids).get();

        } catch (Exception e) {
            throw new RuntimeException("Qdrant deleteDocument failed for documentId=" + documentId, e);
        }
    }

    public void deleteAllForUser(UUID userId) {
        if (userId == null) return;

        try {
            Points.Filter filter = buildUserOnlyFilter(userId);
            List<Points.RetrievedPoint> points = scrollAll(filter);
            if (points.isEmpty()) return;

            List<Points.PointId> ids = points.stream()
                    .map(Points.RetrievedPoint::getId)
                    .collect(Collectors.toList());

            client.deleteAsync(collectionName, ids).get();
        } catch (Exception e) {
            throw new RuntimeException("Qdrant deleteAllForUser failed for userId=" + userId, e);
        }
    }

    // ---------------- FILTERS ----------------

    private Points.Filter buildUserScopedFilter(UUID userId, List<UUID> documentIds) {
        Points.Filter.Builder filter = Points.Filter.newBuilder();
        filter.addMust(userIdCondition(userId));

        if (documentIds != null && !documentIds.isEmpty()) {
            // OR over doc ids, AND with user id
            for (UUID docId : documentIds) {
                filter.addShould(documentIdCondition(docId));
            }
        }

        return filter.build();
    }

    private Points.Filter buildUserAndDocumentFilter(UUID userId, UUID documentId) {
        return Points.Filter.newBuilder()
                .addMust(userIdCondition(userId))
                .addMust(documentIdCondition(documentId))
                .build();
    }

    private Points.Filter buildUserOnlyFilter(UUID userId) {
        return Points.Filter.newBuilder()
                .addMust(userIdCondition(userId))
                .build();
    }

    private Points.Condition userIdCondition(UUID userId) {
        return keywordCondition("userId", userId.toString());
    }

    private Points.Condition documentIdCondition(UUID documentId) {
        return keywordCondition("documentId", documentId.toString());
    }

    private Points.Condition keywordCondition(String key, String keyword) {
        return Points.Condition.newBuilder()
                .setField(
                        Points.FieldCondition.newBuilder()
                                .setKey(key)
                                .setMatch(
                                        Points.Match.newBuilder()
                                                .setKeyword(keyword)
                                                .build()
                                )
                                .build()
                )
                .build();
    }

    // ---------------- SCROLL ----------------

    private List<Points.RetrievedPoint> scrollAll(Points.Filter filter) throws Exception {
        List<Points.RetrievedPoint> all = new ArrayList<>();
        Points.ScrollPoints.Builder req = Points.ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setLimit(256)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());

        if (filter != null) req.setFilter(filter);

        Points.ScrollResponse resp = client.scrollAsync(req.build()).get();
        all.addAll(resp.getResultList());

        while (resp.getNextPageOffset() != null) {
            req.setOffset(resp.getNextPageOffset());
            resp = client.scrollAsync(req.build()).get();
            all.addAll(resp.getResultList());
        }

        return all;
    }
}

package com.chatassistant.aichatassistant.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.chatassistant.aichatassistant.dto.RetrievalResult;
import com.chatassistant.aichatassistant.dto.RetrievedChunk;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

@Service
public class QdrantService {

    private static final Logger logger = LoggerFactory.getLogger(QdrantService.class);
    private static final int VECTOR_SIZE = 768;

    private final QdrantClient client;
    private final String collectionName;

    public QdrantService(
            QdrantClient client,
            @Value("${qdrant.collection}") String collectionName
    ) {
        this.client = client;
        this.collectionName = collectionName;
    }

    // ======================== UPSERT ========================

    public void upsertChunk(
            UUID userId,
            UUID documentId,
            int chunkIndex,
            List<Float> vector,
            String content,
            String filename
    ) {
        if (userId == null || documentId == null) throw new IllegalArgumentException("userId/documentId required");
        if (vector == null || vector.size() != VECTOR_SIZE) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch: expected " + VECTOR_SIZE + ", got " + (vector == null ? 0 : vector.size()));
        }

        try {
            // Deterministic point ID: hash of (documentId:chunkIndex)
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

    // ======================== SEARCH ========================
    //
    // MULTI-TENANCY CONTRACT:
    //   Every search is scoped to a single userId (MUST condition).
    //   Optional documentIds further narrow results WITHIN that user's data.
    //   A user can NEVER see another user's chunks — the userId filter is non-negotiable.
    //

    public List<String> search(
            UUID userId,
            List<Float> queryVector,
            int limit,
            List<UUID> documentIds
    ) {
        if (userId == null) return List.of();
        if (queryVector == null || queryVector.isEmpty()) return List.of();
        if (queryVector.size() != VECTOR_SIZE) {
            throw new IllegalArgumentException(
                    "Query vector dimension mismatch: expected " + VECTOR_SIZE + ", got " + queryVector.size());
        }
        if (limit <= 0) return List.of();

        try {
            Points.Filter filter = buildSearchFilter(userId, documentIds);

            Points.SearchPoints request = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryVector)
                    .setLimit(limit)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setFilter(filter)
                    .build();

            List<Points.ScoredPoint> results = client.searchAsync(request).get();

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

    /**
     * Like search(), but also extracts the "filename" payload from each scored point.
     * Returns a RetrievalResult containing both chunk texts and their source filenames.
     */
    public RetrievalResult searchWithSources(
            UUID userId,
            List<Float> queryVector,
            int limit,
            List<UUID> documentIds
    ) {
        if (userId == null || queryVector == null || queryVector.isEmpty() || limit <= 0) {
            return new RetrievalResult(List.of(), List.of());
        }

        try {
            Points.Filter filter = buildSearchFilter(userId, documentIds);

            Points.SearchPoints request = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryVector)
                    .setLimit(limit)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setFilter(filter)
                    .build();

            List<Points.ScoredPoint> results = client.searchAsync(request).get();

            List<String> chunks = new ArrayList<>();
            Set<String> filenameSet = new LinkedHashSet<>(); // preserve order, deduplicate

            for (Points.ScoredPoint p : results) {
                var payload = p.getPayloadMap();

                var contentVal = payload.get("content");
                if (contentVal != null && !contentVal.getStringValue().isBlank()) {
                    chunks.add(contentVal.getStringValue());
                }

                var filenameVal = payload.get("filename");
                if (filenameVal != null && !filenameVal.getStringValue().isBlank()) {
                    filenameSet.add(filenameVal.getStringValue());
                }
            }

            return new RetrievalResult(chunks, new ArrayList<>(filenameSet));

        } catch (Exception e) {
            throw new RuntimeException("Qdrant searchWithSources failed", e);
        }
    }

    /**
     * Like searchWithSources(), but returns one structured RetrievedChunk per hit —
     * with documentId, filename, chunkIndex, content, and similarity score. Used by
     * the grounded-RAG path to build verifiable citations.
     *
     * Tenant isolation still enforced via the userId MUST filter.
     */
    public List<RetrievedChunk> searchWithCitations(
            UUID userId,
            List<Float> queryVector,
            int limit,
            List<UUID> documentIds
    ) {
        if (userId == null || queryVector == null || queryVector.isEmpty() || limit <= 0) {
            return List.of();
        }
        if (queryVector.size() != VECTOR_SIZE) {
            throw new IllegalArgumentException(
                    "Query vector dimension mismatch: expected " + VECTOR_SIZE + ", got " + queryVector.size());
        }

        try {
            Points.Filter filter = buildSearchFilter(userId, documentIds);

            Points.SearchPoints request = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryVector)
                    .setLimit(limit)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setFilter(filter)
                    .build();

            List<Points.ScoredPoint> results = client.searchAsync(request).get();

            List<RetrievedChunk> chunks = new ArrayList<>(results.size());
            for (Points.ScoredPoint p : results) {
                var payload = p.getPayloadMap();
                String docIdStr = stringField(payload, "documentId");
                String content  = stringField(payload, "content");
                String filename = stringField(payload, "filename");
                int chunkIndex  = intField(payload, "chunkIndex");

                if (docIdStr.isEmpty() || content.isEmpty()) continue;

                chunks.add(new RetrievedChunk(
                        UUID.fromString(docIdStr),
                        filename,
                        chunkIndex,
                        content,
                        null,            // page not currently captured
                        p.getScore()
                ));
            }
            return chunks;

        } catch (Exception e) {
            throw new RuntimeException("Qdrant searchWithCitations failed", e);
        }
    }

    private static String stringField(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload, String key) {
        var v = payload.get(key);
        return v == null ? "" : v.getStringValue();
    }

    private static int intField(Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload, String key) {
        var v = payload.get(key);
        return v == null ? 0 : (int) v.getIntegerValue();
    }

    // ======================== DELETE ========================
    //
    // Uses Qdrant's filter-based deletion — no need to scroll and collect IDs.
    // The filter guarantees userId scoping, so one tenant cannot delete another's data.
    //

    public void deleteDocument(UUID userId, UUID documentId) {
        if (userId == null || documentId == null) return;

        try {
            Points.Filter filter = Points.Filter.newBuilder()
                    .addMust(matchKeyword("userId", userId.toString()))
                    .addMust(matchKeyword("documentId", documentId.toString()))
                    .build();

            client.deleteAsync(collectionName, filter).get();
            logger.info("Deleted vectors for documentId={} userId={}", documentId, userId);

        } catch (Exception e) {
            throw new RuntimeException("Qdrant deleteDocument failed for documentId=" + documentId, e);
        }
    }

    public void deleteAllForUser(UUID userId) {
        if (userId == null) return;

        try {
            Points.Filter filter = Points.Filter.newBuilder()
                    .addMust(matchKeyword("userId", userId.toString()))
                    .build();

            client.deleteAsync(collectionName, filter).get();
            logger.info("Deleted all vectors for userId={}", userId);

        } catch (Exception e) {
            throw new RuntimeException("Qdrant deleteAllForUser failed for userId=" + userId, e);
        }
    }

    // ======================== FILTER BUILDING ========================
    //
    // KEY DESIGN DECISION:
    //   We use TWO must conditions — never should.
    //   must[0] = userId  (tenant isolation — always present)
    //   must[1] = documentId IN [...]  (optional document scoping via matchAny)
    //
    //   This makes the filter logic unambiguous:
    //     "Give me chunks WHERE userId == X AND documentId IN (a, b, c)"
    //

    private Points.Filter buildSearchFilter(UUID userId, List<UUID> documentIds) {
        Points.Filter.Builder filter = Points.Filter.newBuilder();

        // MUST #1: Tenant isolation — always enforced
        filter.addMust(matchKeyword("userId", userId.toString()));

        // MUST #2: Optional document scoping — narrow within the tenant's data
        if (documentIds != null && !documentIds.isEmpty()) {
            List<String> docIdStrings = documentIds.stream()
                    .map(UUID::toString)
                    .toList();

            filter.addMust(matchAnyKeyword("documentId", docIdStrings));
        }

        return filter.build();
    }

    // Match a single keyword value: field == value
    private Points.Condition matchKeyword(String field, String keyword) {
        return Points.Condition.newBuilder()
                .setField(
                        Points.FieldCondition.newBuilder()
                                .setKey(field)
                                .setMatch(
                                        Points.Match.newBuilder()
                                                .setKeyword(keyword)
                                                .build()
                                )
                                .build()
                )
                .build();
    }

    // Match any of multiple keyword values: field IN [value1, value2, ...]
    private Points.Condition matchAnyKeyword(String field, List<String> keywords) {
        return Points.Condition.newBuilder()
                .setField(
                        Points.FieldCondition.newBuilder()
                                .setKey(field)
                                .setMatch(
                                        Points.Match.newBuilder()
                                                .setKeywords(
                                                        Points.RepeatedStrings.newBuilder()
                                                                .addAllStrings(keywords)
                                                                .build()
                                                )
                                                .build()
                                )
                                .build()
                )
                .build();
    }

    // ======================== SCROLL ========================

    private List<Points.PointId> scrollAllIds(Points.Filter filter) throws Exception {
        List<Points.PointId> allIds = new ArrayList<>();

        Points.ScrollPoints.Builder req = Points.ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setLimit(256)
                .setFilter(filter)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(false).build());

        Points.ScrollResponse resp = client.scrollAsync(req.build()).get();
        resp.getResultList().forEach(p -> allIds.add(p.getId()));

        while (resp.getNextPageOffset() != null) {
            req.setOffset(resp.getNextPageOffset());
            resp = client.scrollAsync(req.build()).get();
            resp.getResultList().forEach(p -> allIds.add(p.getId()));
        }

        return allIds;
    }
}

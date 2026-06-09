package com.chatassistant.aichatassistant.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class QdrantConfig {

    private static final Logger logger = LoggerFactory.getLogger(QdrantConfig.class);
    private static final int VECTOR_SIZE = 768;

    @Value("${qdrant.host}")
    private String host;

    @Value("${qdrant.port}")
    private int port;

    @Value("${qdrant.collection}")
    private String collectionName;

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient() {
        logger.info("Connecting to Qdrant gRPC at {}:{}", host, port);

        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, false).build()
        );

        ensureCollection(client);
        logger.info("Qdrant ready. Collection='{}'", collectionName);
        return client;
    }

    private void ensureCollection(QdrantClient client) {
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

            logger.info("Created Qdrant collection '{}' (dim={}, cosine)", collectionName, VECTOR_SIZE);

        } catch (Exception e) {
            throw new RuntimeException("Failed to create/verify Qdrant collection: " + collectionName, e);
        }
    }
}

package com.chatassistant.aichatassistant.service;

import com.chatassistant.aichatassistant.exception.ServiceUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for {@link OllamaService#chatStructured}.
 *
 * Uses a real loopback HttpServer instead of a mocked HttpClient — the production code
 * builds its HttpClient internally, so this is both simpler and closer to reality.
 * The fake server returns canned /api/chat responses and records the request bodies it
 * receives, so we can assert that validation errors get fed back into the retry prompt.
 */
class OllamaServiceStructuredTest {

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestBodies = new ArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        callCount.set(0);
        requestBodies.clear();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private OllamaService newService() {
        return new OllamaService(baseUrl, "test-model", mapper, validator);
    }

    /** Wraps a model-emitted JSON string into a full /api/chat response body. */
    private String chatResponseBody(String content) throws IOException {
        String escaped = mapper.writeValueAsString(content);
        return "{\"message\":{\"role\":\"assistant\",\"content\":" + escaped + "},\"done\":true}";
    }

    private void respondWith(String[] modelOutputs) {
        server.createContext("/api/chat", ex -> {
            requestBodies.add(new String(ex.getRequestBody().readAllBytes()));
            int n = callCount.getAndIncrement();
            String content = modelOutputs[Math.min(n, modelOutputs.length - 1)];
            byte[] body = chatResponseBody(content).getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    // ---------------- tests ----------------

    @Test
    void chatStructured_retriesAndSucceeds_whenFirstResponseFailsValidation() throws Exception {
        respondWith(new String[]{
                "{\"name\":\"\",\"age\":-3}",         // fails @NotBlank + @Min
                "{\"name\":\"Alice\",\"age\":30}"     // valid
        });

        Person p = newService().chatStructured("system", "extract a person", Person.class);

        assertEquals("Alice", p.name());
        assertEquals(30, p.age());
        assertEquals(2, callCount.get(), "should have retried exactly once");

        JsonNode retryBody = mapper.readTree(requestBodies.get(1));
        String secondUserMsg = retryBody.path("messages").get(1).path("content").asText();
        assertTrue(secondUserMsg.contains("failed validation"),
                "retry user message must include validation context: " + secondUserMsg);
        assertEquals("json", retryBody.path("format").asText(),
                "format:\"json\" must be sent on every attempt");
    }

    @Test
    void chatStructured_retriesAndSucceeds_whenFirstResponseIsNotJson() throws Exception {
        respondWith(new String[]{
                "this is not JSON, sorry",            // parse failure
                "{\"name\":\"Bob\",\"age\":42}"       // valid
        });

        Person p = newService().chatStructured("system", "extract a person", Person.class);

        assertEquals("Bob", p.name());
        assertEquals(2, callCount.get());
        String secondUserMsg = mapper.readTree(requestBodies.get(1))
                .path("messages").get(1).path("content").asText();
        assertTrue(secondUserMsg.contains("not valid JSON"),
                "retry must mention the parse failure: " + secondUserMsg);
    }

    @Test
    void chatStructured_throwsAfterMaxRetries_whenAllAttemptsFailValidation() {
        respondWith(new String[]{"{\"name\":\"\",\"age\":-1}"});  // always fails

        assertThrows(ServiceUnavailableException.class, () ->
                newService().chatStructured("system", "extract", Person.class));

        // MAX_STRUCTURED_RETRIES = 2 → 3 total attempts (initial + 2 retries)
        assertEquals(3, callCount.get(), "should have made exactly MAX_STRUCTURED_RETRIES + 1 attempts");
    }

    // ---------------- fixture ----------------

    public record Person(@NotBlank String name, @Min(0) int age) {}
}

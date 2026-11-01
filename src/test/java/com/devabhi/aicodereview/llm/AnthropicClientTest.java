package com.devabhi.aicodereview.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.devabhi.aicodereview.review.port.LlmException;
import com.sun.net.httpserver.HttpServer;

class AnthropicClientTest {

    private static final String API_KEY = "sk-test-key-123456";

    private static final String OK_BODY = """
            {"id": "msg_1", "type": "message", "role": "assistant", "model": "test-model-2",
             "content": [
               {"type": "text", "text": "thinking out loud"},
               {"type": "tool_use", "id": "tu_1", "name": "report_findings",
                "input": {"summary": "ok", "findings": []}}],
             "stop_reason": "tool_use",
             "usage": {"input_tokens": 120, "output_tokens": 34}}
            """;

    private static final String NO_TOOL_BODY = """
            {"model": "test-model", "content": [{"type": "text", "text": "I refuse"}],
             "usage": {"input_tokens": 10, "output_tokens": 2}}
            """;

    private static final String AUTH_ERROR_BODY =
            "{\"type\": \"error\", \"error\": {\"type\": \"authentication_error\", \"message\": \"bad key\"}}";

    private record Reply(int status, String body, Map<String, String> headers, long delayMs) {
        static Reply ok(String body) {
            return new Reply(200, body, Map.of(), 0);
        }

        static Reply status(int status) {
            return new Reply(status, "{}", Map.of(), 0);
        }
    }

    private record Received(Map<String, String> headers, String body) {
    }

    /** Local stand-in for api.anthropic.com that plays back a script and records requests. */
    private static final class FakeServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final Deque<Reply> script;
        final List<Received> received = new CopyOnWriteArrayList<>();

        FakeServer(Reply... replies) throws IOException {
            script = new ArrayDeque<>(Arrays.asList(replies));
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(executor);
            server.createContext("/v1/messages", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Map<String, String> headers = new HashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
                received.add(new Received(headers, new String(body, StandardCharsets.UTF_8)));

                Reply reply;
                synchronized (script) {
                    reply = script.poll();
                }
                if (reply == null) {
                    reply = Reply.status(500);
                }
                try {
                    if (reply.delayMs() > 0) {
                        Thread.sleep(reply.delayMs());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
                exchange.getResponseHeaders().add("content-type", "application/json");
                byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(reply.status(), out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static ModelRequest request() {
        return new ModelRequest("system text", "user text", "report_findings", "Report findings.",
                ReviewTool.SCHEMA_JSON, 4096);
    }

    private static AnthropicClient client(FakeServer server, List<Long> sleeps, Duration timeout, int attempts) {
        AnthropicConfig config = new AnthropicConfig(API_KEY, server.baseUrl(), "test-model", timeout);
        RetryPolicy retry = new RetryPolicy(attempts, 1_000, 30_000, () -> 1.0, sleeps::add);
        return new AnthropicClient(config, retry);
    }

    @Test
    void sendsTheDocumentedRequestAndParsesTheReply() throws Exception {
        try (FakeServer server = new FakeServer(Reply.ok(OK_BODY))) {
            ModelReply reply = client(server, new ArrayList<>(), Duration.ofSeconds(5), 3).complete(request());

            assertThat(reply.toolInputJson()).contains("\"summary\"").contains("\"findings\"");
            assertThat(reply.model()).isEqualTo("test-model-2");
            assertThat(reply.inputTokens()).isEqualTo(120);
            assertThat(reply.outputTokens()).isEqualTo(34);
            assertThat(reply.latencyMs()).isNotNegative();

            assertThat(server.received).hasSize(1);
            Received r = server.received.get(0);
            assertThat(r.headers().get("x-api-key")).isEqualTo(API_KEY);
            assertThat(r.headers().get("anthropic-version")).isEqualTo("2023-06-01");
            assertThat(r.headers().get("content-type")).contains("application/json");
            assertThat(r.body())
                    .contains("\"model\":\"test-model\"")
                    .contains("\"max_tokens\":4096")
                    .contains("\"system\":\"system text\"")
                    .contains("\"role\":\"user\",\"content\":\"user text\"")
                    .contains("\"tool_choice\":{\"type\":\"tool\",\"name\":\"report_findings\"}")
                    .contains("\"input_schema\"");
        }
    }

    @Test
    void missingToolCallGivesNullInputSoTheAdapterCanRetry() throws Exception {
        try (FakeServer server = new FakeServer(Reply.ok(NO_TOOL_BODY))) {
            ModelReply reply = client(server, new ArrayList<>(), Duration.ofSeconds(5), 3).complete(request());
            assertThat(reply.toolInputJson()).isNull();
            assertThat(reply.inputTokens()).isEqualTo(10);
        }
    }

    @Test
    void honorsRetryAfterOn429() throws Exception {
        Reply rateLimited = new Reply(429, "{}", Map.of("retry-after", "1"), 0);
        try (FakeServer server = new FakeServer(rateLimited, Reply.ok(OK_BODY))) {
            List<Long> sleeps = new ArrayList<>();
            ModelReply reply = client(server, sleeps, Duration.ofSeconds(5), 3).complete(request());

            assertThat(reply.toolInputJson()).isNotNull();
            assertThat(server.received).hasSize(2);
            assertThat(sleeps).containsExactly(1_000L);
        }
    }

    @Test
    void retriesServerErrorsWithExponentialBackoff() throws Exception {
        try (FakeServer server = new FakeServer(Reply.status(500), Reply.status(503), Reply.ok(OK_BODY))) {
            List<Long> sleeps = new ArrayList<>();
            ModelReply reply = client(server, sleeps, Duration.ofSeconds(5), 3).complete(request());

            assertThat(reply.toolInputJson()).isNotNull();
            assertThat(server.received).hasSize(3);
            assertThat(sleeps).containsExactly(1_000L, 2_000L);
        }
    }

    @Test
    void givesUpAfterMaxAttempts() throws Exception {
        try (FakeServer server = new FakeServer(Reply.status(500), Reply.status(500), Reply.status(500))) {
            AnthropicClient client = client(server, new ArrayList<>(), Duration.ofSeconds(5), 3);
            assertThatThrownBy(() -> client.complete(request()))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("unavailable after 3 attempts")
                    .hasMessageContaining("HTTP 500");
            assertThat(server.received).hasSize(3);
        }
    }

    @Test
    void authFailureIsNotRetriedAndNeverLeaksTheKey() throws Exception {
        try (FakeServer server = new FakeServer(new Reply(401, AUTH_ERROR_BODY, Map.of(), 0))) {
            List<Long> sleeps = new ArrayList<>();
            AnthropicClient client = client(server, sleeps, Duration.ofSeconds(5), 3);
            assertThatThrownBy(() -> client.complete(request()))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("401")
                    .hasMessageContaining("authentication_error")
                    .hasMessageNotContaining(API_KEY)
                    .hasMessageNotContaining("bad key");
            assertThat(server.received).hasSize(1);
            assertThat(sleeps).isEmpty();
        }
    }

    @Test
    void timeoutIsRetriedThenFails() throws Exception {
        Reply slow = new Reply(200, OK_BODY, Map.of(), 1_500);
        try (FakeServer server = new FakeServer(slow, slow)) {
            AnthropicClient client = client(server, new ArrayList<>(), Duration.ofMillis(300), 2);
            assertThatThrownBy(() -> client.complete(request()))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("unavailable after 2 attempts")
                    .hasMessageContaining("timeout");
            assertThat(server.received).hasSize(2);
        }
    }

    @Test
    void unreadableSuccessBodyBecomesAnLlmException() throws Exception {
        try (FakeServer server = new FakeServer(Reply.ok("this is not json"))) {
            AnthropicClient client = client(server, new ArrayList<>(), Duration.ofSeconds(5), 3);
            assertThatThrownBy(() -> client.complete(request()))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("unreadable response")
                    .hasMessageNotContaining("this is not json");
        }
    }

    @Test
    void configNeverShowsTheApiKey() {
        AnthropicConfig config = AnthropicConfig.of(API_KEY, "some-model");
        assertThat(config.toString()).doesNotContain(API_KEY).contains("***");
    }

    @Test
    void blankKeyOrModelIsRejected() {
        assertThatThrownBy(() -> AnthropicConfig.of(" ", "m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnthropicConfig.of("k", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
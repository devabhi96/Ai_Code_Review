package com.devabhi.aicodereview.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.devabhi.aicodereview.review.port.LlmException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the Anthropic Messages API with a forced tool. Every error message is fixed text:
 * never the prompt, the response body or the API key.
 */
public final class AnthropicClient implements StructuredModelClient {

    static final String API_VERSION = "2023-06-01";

    private static final Set<Integer> RETRYABLE = Set.of(408, 429, 500, 502, 503, 504, 529);
    private static final Pattern SAFE_ERROR_TYPE = Pattern.compile("[a-z_]{1,40}");

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BlockDto(String type, String name, JsonNode input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageDto(@JsonProperty("input_tokens") Integer inputTokens,
                    @JsonProperty("output_tokens") Integer outputTokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResponseDto(String model, List<BlockDto> content, UsageDto usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorInner(String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorDto(ErrorInner error) {
    }

    private final AnthropicConfig config;
    private final RetryPolicy retry;
    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AnthropicClient(AnthropicConfig config, RetryPolicy retry) {
        this.config = config;
        this.retry = retry;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.timeout())
                .build();
    }

    public AnthropicClient(AnthropicConfig config) {
        this(config, RetryPolicy.defaults());
    }

    @Override
    public ModelReply complete(ModelRequest request) {
        long start = System.nanoTime();
        String body = buildBody(request);
        String last = "unknown";

        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            Long retryAfterMs = null;
            try {
                HttpResponse<String> response = http.send(buildHttpRequest(body),
                        HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    return toReply(response.body(), request, (System.nanoTime() - start) / 1_000_000);
                }
                if (!RETRYABLE.contains(status)) {
                    throw new LlmException("model API returned HTTP " + status
                            + " (" + errorType(response.body()) + ")");
                }
                last = "HTTP " + status;
                retryAfterMs = retryAfter(response);
            } catch (HttpTimeoutException e) {
                last = "timeout";
            } catch (IOException e) {
                last = "network error";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("model call interrupted");
            }
            if (attempt < retry.maxAttempts()) {
                try {
                    retry.sleep(retry.delayMs(attempt, retryAfterMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("model call interrupted");
                }
            }
        }
        throw new LlmException("model API unavailable after " + retry.maxAttempts()
                + " attempts (last: " + last + ")");
    }

    private String buildBody(ModelRequest r) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", r.toolName());
        tool.put("description", r.toolDescription());
        tool.put("input_schema", mapper.readTree(r.toolSchemaJson()));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", r.user());

        Map<String, Object> toolChoice = new LinkedHashMap<>();
        toolChoice.put("type", "tool");
        toolChoice.put("name", r.toolName());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("max_tokens", r.maxOutputTokens());
        body.put("system", r.system());
        body.put("messages", List.of(message));
        body.put("tools", List.of(tool));
        body.put("tool_choice", toolChoice);
        return mapper.writeValueAsString(body);
    }

    private HttpRequest buildHttpRequest(String body) {
        String base = config.baseUrl().endsWith("/")
                ? config.baseUrl().substring(0, config.baseUrl().length() - 1)
                : config.baseUrl();
        return HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                .timeout(config.timeout())
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private ModelReply toReply(String body, ModelRequest request, long latencyMs) {
        ResponseDto dto;
        try {
            dto = mapper.readValue(body, ResponseDto.class);
        } catch (RuntimeException e) {
            throw new LlmException("unreadable response from model API");
        }
        if (dto == null) {
            throw new LlmException("unreadable response from model API");
        }
        String toolInput = null;
        if (dto.content() != null) {
            for (BlockDto block : dto.content()) {
                if (block != null && "tool_use".equals(block.type())
                        && request.toolName().equals(block.name()) && block.input() != null) {
                    toolInput = block.input().toString();
                    break;
                }
            }
        }
        int in = dto.usage() == null || dto.usage().inputTokens() == null ? 0 : dto.usage().inputTokens();
        int out = dto.usage() == null || dto.usage().outputTokens() == null ? 0 : dto.usage().outputTokens();
        // A missing tool call yields null input; the adapter turns that into a corrective retry.
        return new ModelReply(toolInput, dto.model() == null ? config.model() : dto.model(), in, out, latencyMs);
    }

    private String errorType(String body) {
        try {
            ErrorDto dto = mapper.readValue(body, ErrorDto.class);
            String type = dto == null || dto.error() == null ? null : dto.error().type();
            return type != null && SAFE_ERROR_TYPE.matcher(type).matches() ? type : "unknown";
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static Long retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after").map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000;
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }
}
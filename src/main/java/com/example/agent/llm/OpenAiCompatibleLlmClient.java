package com.example.agent.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.example.agent.config.AgentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容的 /chat/completions，纯手写 HTTP，不引任何 SDK。
 * complete() 一次性取全文；completeStream() 消费 SSE，边收边回调。
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final AgentProperties properties;
    private final HttpClient httpClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(AgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        String apiKey = requireApiKey();

        long started = System.nanoTime();
        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(systemPrompt, userPrompt, false))
                .retrieve()
                .body(JsonNode.class);
        long costMs = (System.nanoTime() - started) / 1_000_000;

        JsonNode content = response == null ? null : response.path("choices").path(0).path("message").path("content");
        if (content == null || content.isMissingNode() || content.asText().isBlank()) {
            log.warn("模型返回为空: {}", response);
            throw new IllegalStateException("模型没有返回内容，检查 model 名称和 base-url 是否正确");
        }
        log.debug("模型响应耗时 {}ms", costMs);
        return content.asText().trim();
    }

    @Override
    public String completeStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        String apiKey = requireApiKey();
        String body;
        try {
            body = objectMapper.writeValueAsString(requestBody(systemPrompt, userPrompt, true));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("请求体序列化失败: " + ex.getMessage(), ex);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(properties.readTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        StringBuilder full = new StringBuilder();
        try {
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("模型网关返回 HTTP " + response.statusCode() + ": "
                            + lines.collect(Collectors.joining("\n")));
                }
                Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    String text = objectMapper.readTree(data)
                            .path("choices").path(0).path("delta").path("content").asText("");
                    if (!text.isEmpty()) {
                        full.append(text);
                        onDelta.accept(text);
                    }
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("流式请求失败: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("流式请求被中断", ex);
        }

        if (full.isEmpty()) {
            log.warn("模型流式返回为空");
            throw new IllegalStateException("模型没有返回内容，检查 model 名称和 base-url 是否正确");
        }
        return full.toString().trim();
    }

    private Map<String, Object> requestBody(String systemPrompt, String userPrompt, boolean stream) {
        return Map.of(
                "model", properties.model(),
                "temperature", properties.temperature(),
                // ReAct 靠解析文本，遇到额外字段直接忽略
                "max_tokens", 1024,
                "stream", stream,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
    }

    private String requireApiKey() {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("还没有配置模型 API Key，请设置环境变量 OPENAI_API_KEY");
        }
        return apiKey;
    }
}

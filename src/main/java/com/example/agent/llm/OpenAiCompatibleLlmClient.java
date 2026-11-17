package com.example.agent.llm;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import com.example.agent.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容的 /chat/completions，纯手写 HTTP，不引任何 SDK。
 */
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final AgentProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleLlmClient(AgentProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
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
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("还没有配置模型 API Key，请设置环境变量 OPENAI_API_KEY");
        }

        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "temperature", properties.temperature(),
                // ReAct 靠解析文本，遇到额外字段直接忽略
                "max_tokens", 1024,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));

        long started = System.nanoTime();
        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
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
}

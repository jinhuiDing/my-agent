package com.gene.agent.llm;

public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        ResponseFormat responseFormat
) {
}

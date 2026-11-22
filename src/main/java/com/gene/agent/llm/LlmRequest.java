package com.gene.agent.llm;

import com.gene.agent.llm.message.Message;

import java.util.List;

public record LlmRequest(
    List<Message> messages,
        ResponseFormat responseFormat
) {
}

package com.gene.agent.llm.message;

public sealed interface Message
        permits SystemMessage, UserMessage, AssistantMessage, ToolMessage {

    Role role();

    String content();
}

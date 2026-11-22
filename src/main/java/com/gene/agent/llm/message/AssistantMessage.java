package com.gene.agent.llm.message;

public record AssistantMessage(String content) implements Message {

    @Override
    public Role role() {
        return Role.ASSISTANT;
    }
}

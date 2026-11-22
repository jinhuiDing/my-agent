package com.gene.agent.llm.message;

public record UserMessage(String content) implements Message {

    @Override
    public Role role() {
        return Role.USER;
    }
}

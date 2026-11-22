package com.gene.agent.llm.message;

public record SystemMessage(String content) implements Message {

    @Override
    public Role role() {
        return Role.SYSTEM;
    }
}

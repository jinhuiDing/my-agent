package com.gene.agent.llm.message;

public record ToolMessage(
        String toolName,
        String content
) implements Message {

    @Override
    public Role role() {
        return Role.TOOL;
    }
}

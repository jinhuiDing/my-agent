package com.gene.agent.output;

public record ActionOutput(
        String thought,
        ActionCall action
) implements AgentOutput {
}

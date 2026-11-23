package com.gene.agent.tool.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.gene.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class AllowAllToolExecutionPolicy implements ToolExecutionPolicy {

    @Override
    public ToolExecutionDecision evaluate(Tool tool, JsonNode input) {
        return ToolExecutionDecision.allow();
    }
}
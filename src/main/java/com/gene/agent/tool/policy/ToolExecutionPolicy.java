package com.gene.agent.tool.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.gene.agent.tool.Tool;

public interface ToolExecutionPolicy {

    ToolExecutionDecision evaluate(Tool tool, JsonNode input);
}
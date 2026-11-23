package com.gene.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;

public record PendingToolCall(
        String thought,
        String toolName,
        JsonNode input,
        String actionInput,
        String approvalId
) {
}
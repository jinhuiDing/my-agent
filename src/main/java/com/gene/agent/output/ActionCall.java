package com.gene.agent.output;

import com.fasterxml.jackson.databind.JsonNode;

public record ActionCall(
        String name,
        JsonNode input
) {
}

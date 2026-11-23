package com.gene.agent.tool.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.gene.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ApprovalRequiredToolPolicy implements ToolExecutionPolicy {

    private final Set<String> toolNames;

    public ApprovalRequiredToolPolicy(
            @Value("${agent.approval-required-tools:}") String configured) {
        this.toolNames = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ToolExecutionDecision evaluate(Tool tool, JsonNode input) {
        if (toolNames.contains(tool.name())) {
            return ToolExecutionDecision.requireApproval("工具 " + tool.name() + " 需要人工审批");
        }
        return ToolExecutionDecision.allow();
    }
}
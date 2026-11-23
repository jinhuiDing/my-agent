package com.gene.agent.tool.policy;

public record ToolExecutionDecision(
        Decision decision,
        String reason
) {

    public enum Decision {
        ALLOW,
        DENY,
        REQUIRE_APPROVAL
    }

    public static ToolExecutionDecision allow() {
        return new ToolExecutionDecision(Decision.ALLOW, null);
    }

    public static ToolExecutionDecision deny(String reason) {
        return new ToolExecutionDecision(Decision.DENY, reason);
    }

    public static ToolExecutionDecision requireApproval(String reason) {
        return new ToolExecutionDecision(Decision.REQUIRE_APPROVAL, reason);
    }
}
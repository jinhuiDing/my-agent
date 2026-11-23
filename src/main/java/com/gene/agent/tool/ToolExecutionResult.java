package com.gene.agent.tool;

public record ToolExecutionResult(
        Status status,
        String output,
        String approvalId
) {

    public enum Status {
        SUCCESS,
        FAILED,
        DENIED,
        WAITING_APPROVAL
    }

    public static ToolExecutionResult success(String output) {
        return new ToolExecutionResult(
                Status.SUCCESS,
                output,
                null
        );
    }

    public static ToolExecutionResult failed(String output) {
        return new ToolExecutionResult(
                Status.FAILED,
                output,
                null
        );
    }

    public static ToolExecutionResult denied(String output) {
        return new ToolExecutionResult(
                Status.DENIED,
                output,
                null
        );
    }

    public static ToolExecutionResult waitingApproval(
            String approvalId,
            String output
    ) {
        return new ToolExecutionResult(
                Status.WAITING_APPROVAL,
                output,
                approvalId
        );
    }
}
package com.gene.agent.tool.approval;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record ApprovalRequest(
        String approvalId,
        String runId,
        String toolName,
        JsonNode toolInput,
        String reason,
        ApprovalStatus status,
        Instant createdAt,
        String reviewer,
        String reviewComment,
        Instant reviewedAt
) {

    public ApprovalRequest {
        toolInput = toolInput == null ? null : toolInput.deepCopy();
    }

    @Override
    public JsonNode toolInput() {
        return toolInput == null ? null : toolInput.deepCopy();
    }

    public ApprovalRequest review(
            ApprovalStatus newStatus,
            String reviewer,
            String reviewComment
    ) {
        if (status != ApprovalStatus.PENDING) {
            throw new IllegalStateException(
                    "审批单当前状态不允许处理: " + status
            );
        }

        if (newStatus != ApprovalStatus.APPROVED
                && newStatus != ApprovalStatus.REJECTED) {
            throw new IllegalArgumentException(
                    "非法审批结果: " + newStatus
            );
        }

        return new ApprovalRequest(
                approvalId,
                runId,
                toolName,
                toolInput,
                reason,
                newStatus,
                createdAt,
                reviewer,
                reviewComment,
                Instant.now()
        );
    }
}
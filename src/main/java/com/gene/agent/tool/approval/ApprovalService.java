package com.gene.agent.tool.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ApprovalService {

    private final ApprovalStore approvalStore;

    public ApprovalService(ApprovalStore approvalStore) {
        this.approvalStore = approvalStore;
    }

    /**
     * 创建审批单
     */
    public ApprovalRequest create(
            String runId,
            String toolName,
            JsonNode toolInput,
            String reason
    ) {
        ApprovalRequest request = new ApprovalRequest(
                "APR-" + UUID.randomUUID(),
                runId,
                toolName,
                toolInput,
                reason,
                ApprovalStatus.PENDING,
                Instant.now(),
                null,
                null,
                null
        );

        approvalStore.save(request);

        return request;
    }

    /**
     * 查询审批单
     */
    public ApprovalRequest get(String approvalId) {
        ApprovalRequest request = approvalStore.findById(approvalId);

        if (request == null) {
            throw new IllegalArgumentException(
                    "审批单不存在: " + approvalId
            );
        }

        return request;
    }

    /**
     * 查询待审批列表
     */
    public List<ApprovalRequest> getPendingApprovals() {
        return approvalStore.findPending();
    }

    /**
     * 批准
     */
    public ApprovalRequest approve(
            String approvalId,
            String reviewer,
            String comment
    ) {
        return review(
                approvalId,
                ApprovalStatus.APPROVED,
                reviewer,
                comment
        );
    }

    /**
     * 拒绝
     */
    public ApprovalRequest reject(
            String approvalId,
            String reviewer,
            String comment
    ) {
        return review(
                approvalId,
                ApprovalStatus.REJECTED,
                reviewer,
                comment
        );
    }

    private ApprovalRequest review(
            String approvalId,
            ApprovalStatus status,
            String reviewer,
            String comment
    ) {
        ApprovalRequest current = get(approvalId);

        ApprovalRequest updated = current.review(
                status,
                reviewer,
                comment
        );


        return approvalStore.update(updated);
    }


}
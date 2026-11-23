package com.gene.agent.web;

import com.gene.agent.agent.AgentListener;
import com.gene.agent.agent.AgentRunResult;
import com.gene.agent.agent.ReactAgent;
import com.gene.agent.tool.approval.ApprovalRequest;
import com.gene.agent.tool.approval.ApprovalService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
class ApprovalController {

    private final ApprovalService approvalService;
    private final ReactAgent agent;

    ApprovalController(ApprovalService approvalService, ReactAgent agent) {
        this.approvalService = approvalService;
        this.agent = agent;
    }

    @GetMapping
    List<ApprovalRequest> listPending() {
        return approvalService.getPendingApprovals();
    }

    @GetMapping("/{approvalId}")
    ApprovalRequest get(@PathVariable String approvalId) {
        return approvalService.get(approvalId);
    }

    @PostMapping("/{approvalId}/approve")
    AgentRunResult approve(@PathVariable String approvalId,
                           @RequestBody(required = false) ApprovalDecisionRequest request) {
        ApprovalRequest approval = approvalService.approve(
                approvalId, reviewerOf(request), commentOf(request));
        return resume(approval);
    }

    @PostMapping("/{approvalId}/reject")
    AgentRunResult reject(@PathVariable String approvalId,
                          @RequestBody(required = false) ApprovalDecisionRequest request) {
        ApprovalRequest approval = approvalService.reject(
                approvalId, reviewerOf(request), commentOf(request));
        return resume(approval);
    }

    private AgentRunResult resume(ApprovalRequest approval) {
        return agent.resume(approval.runId(), approval.approvalId(), new AgentListener() {
        });
    }

    private static String reviewerOf(ApprovalDecisionRequest request) {
        if (request == null || request.reviewer() == null || request.reviewer().isBlank()) {
            return "web";
        }
        return request.reviewer().trim();
    }

    private static String commentOf(ApprovalDecisionRequest request) {
        return request == null ? null : request.comment();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> handleNotFound(IllegalArgumentException ex) {
        return error(ex);
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> handleConflict(IllegalStateException ex) {
        return error(ex);
    }

    private static Map<String, String> error(RuntimeException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
    }

    record ApprovalDecisionRequest(String reviewer, String comment) {
    }
}

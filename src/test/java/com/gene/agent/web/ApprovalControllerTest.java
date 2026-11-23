package com.gene.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gene.agent.agent.AgentListener;
import com.gene.agent.agent.AgentRunResult;
import com.gene.agent.agent.ReactAgent;
import com.gene.agent.tool.approval.ApprovalRequest;
import com.gene.agent.tool.approval.ApprovalService;
import com.gene.agent.tool.approval.ApprovalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalControllerTest {

    private ApprovalService approvalService;
    private ReactAgent agent;
    private ApprovalController controller;

    @BeforeEach
    void setUp() {
        approvalService = mock(ApprovalService.class);
        agent = mock(ReactAgent.class);
        controller = new ApprovalController(approvalService, agent);
    }

    @Test
    void shouldListPendingAndGetApproval() {
        ApprovalRequest pending = approval(ApprovalStatus.PENDING);
        when(approvalService.getPendingApprovals()).thenReturn(List.of(pending));
        when(approvalService.get("APR-1")).thenReturn(pending);

        assertEquals(List.of(pending), controller.listPending());
        assertSame(pending, controller.get("APR-1"));
    }

    @Test
    void shouldApproveAndResumeRun() {
        ApprovalRequest approved = approval(ApprovalStatus.APPROVED);
        AgentRunResult expected = result("final_answer", true);
        when(approvalService.approve("APR-1", "web", null)).thenReturn(approved);
        when(agent.resume(eq("run-1"), eq("APR-1"), any(AgentListener.class))).thenReturn(expected);

        AgentRunResult actual = controller.approve("APR-1", null);

        assertSame(expected, actual);
        verify(agent).resume(eq("run-1"), eq("APR-1"), any(AgentListener.class));
    }

    @Test
    void shouldRejectAndResumeRun() {
        ApprovalRequest rejected = approval(ApprovalStatus.REJECTED);
        AgentRunResult expected = result("final_answer", true);
        when(approvalService.reject("APR-1", "tester", "不允许")).thenReturn(rejected);
        when(agent.resume(eq("run-1"), eq("APR-1"), any(AgentListener.class))).thenReturn(expected);

        AgentRunResult actual = controller.reject(
                "APR-1", new ApprovalController.ApprovalDecisionRequest("tester", "不允许"));

        assertSame(expected, actual);
        verify(agent).resume(eq("run-1"), eq("APR-1"), any(AgentListener.class));
    }

    private static ApprovalRequest approval(ApprovalStatus status) {
        return new ApprovalRequest(
                "APR-1",
                "run-1",
                "calculator",
                new ObjectMapper().createObjectNode().put("expression", "1 + 1"),
                "需要审批",
                status,
                Instant.now(),
                status == ApprovalStatus.PENDING ? null : "web",
                null,
                status == ApprovalStatus.PENDING ? null : Instant.now()
        );
    }

    private static AgentRunResult result(String stopReason, boolean solved) {
        return new AgentRunResult("run-1", "问题", "答案", List.of(), solved, stopReason);
    }
}

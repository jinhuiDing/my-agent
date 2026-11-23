package com.gene.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gene.agent.agent.AgentListener;
import com.gene.agent.agent.AgentRunResult;
import com.gene.agent.agent.ReactAgent;
import com.gene.agent.runtime.RunRegistry;
import com.gene.agent.tool.approval.ApprovalRequest;
import com.gene.agent.tool.approval.ApprovalService;
import com.gene.agent.tool.approval.ApprovalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalControllerTest {

    private ApprovalService approvalService;
    private ReactAgent agent;
    private ApprovalController controller;
    private AtomicReference<Runnable> pendingTask;
    private RunRegistry runRegistry;

    @BeforeEach
    void setUp() {
        approvalService = mock(ApprovalService.class);
        agent = mock(ReactAgent.class);
        pendingTask = new AtomicReference<>();
        runRegistry = mock(RunRegistry.class);
        controller = new ApprovalController(approvalService, agent, pendingTask::set, runRegistry);
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
        when(agent.resume(eq("run-1"), eq("APR-1"), any(AgentListener.class))).thenAnswer(invocation -> {
            AgentListener listener = invocation.getArgument(2);
            listener.onToolStart("calculator", "{}");
            listener.onToolComplete("calculator", true, "2", 1);
            listener.onObservation("2");
            listener.onToolStart("calculator", "{}");
            listener.onToolComplete("calculator", false, "后续步骤", 1);
            return expected;
        });

        ApprovalController.ApprovalDecisionResponse actual = controller.approve("APR-1", null);

        assertSame(expected, actual.result());
        assertEquals(ApprovalStatus.APPROVED, actual.approval().status());
        assertTrue(actual.toolExecuted());
        assertEquals(Boolean.TRUE, actual.toolSuccess());
        assertEquals("2", actual.toolResult());
        verify(agent).resume(eq("run-1"), eq("APR-1"), any(AgentListener.class));
    }

    @Test
    void shouldRejectAndResumeRun() {
        ApprovalRequest rejected = approval(ApprovalStatus.REJECTED);
        AgentRunResult expected = result("final_answer", true);
        when(approvalService.reject("APR-1", "tester", "不允许")).thenReturn(rejected);
        when(agent.resume(eq("run-1"), eq("APR-1"), any(AgentListener.class))).thenAnswer(invocation -> {
            AgentListener listener = invocation.getArgument(2);
            listener.onObservation("工具调用被拒绝");
            return expected;
        });

        ApprovalController.ApprovalDecisionResponse actual = controller.reject(
                "APR-1", new ApprovalController.ApprovalDecisionRequest("tester", "不允许"));

        assertSame(expected, actual.result());
        assertEquals(ApprovalStatus.REJECTED, actual.approval().status());
        assertFalse(actual.toolExecuted());
        assertNull(actual.toolSuccess());
        assertNull(actual.toolResult());
        verify(agent).resume(eq("run-1"), eq("APR-1"), any(AgentListener.class));
    }

    @Test
    void shouldReportFailedToolExecution() {
        ApprovalRequest approved = approval(ApprovalStatus.APPROVED);
        AgentRunResult expected = result("final_answer", true);
        when(approvalService.approve("APR-1", "web", null)).thenReturn(approved);
        when(agent.resume(eq("run-1"), eq("APR-1"), any(AgentListener.class))).thenAnswer(invocation -> {
            AgentListener listener = invocation.getArgument(2);
            listener.onToolStart("calculator", "{}");
            listener.onToolComplete("calculator", false, "表达式错误", 1);
            listener.onObservation("表达式错误");
            return expected;
        });

        ApprovalController.ApprovalDecisionResponse actual = controller.approve("APR-1", null);

        assertTrue(actual.toolExecuted());
        assertEquals(Boolean.FALSE, actual.toolSuccess());
        assertEquals("表达式错误", actual.toolResult());
    }

    @Test
    void shouldReportApprovedButToolNotExecuted() {
        ApprovalRequest approved = approval(ApprovalStatus.APPROVED);
        AgentRunResult expected = result("final_answer", true);
        when(approvalService.approve("APR-1", "web", null)).thenReturn(approved);
        when(agent.resume(eq("run-1"), eq("APR-1"), any(AgentListener.class))).thenAnswer(invocation -> {
            AgentListener listener = invocation.getArgument(2);
            listener.onObservation("未找到工具：calculator");
            return expected;
        });

        ApprovalController.ApprovalDecisionResponse actual = controller.approve("APR-1", null);

        assertFalse(actual.toolExecuted());
        assertNull(actual.toolSuccess());
        assertNull(actual.toolResult());
    }

    @Test
    void shouldStreamApproveAndResumeRun() {
        ApprovalRequest approved = approval(ApprovalStatus.APPROVED);
        AgentRunResult expected = result("final_answer", true);
        when(approvalService.approve("APR-1", "web", null)).thenReturn(approved);
        when(agent.resume(eq("run-1"), eq("APR-1"), any(AgentListener.class))).thenAnswer(invocation -> {
            AgentListener listener = invocation.getArgument(2);
            listener.onToolStart("calculator", "{}");
            listener.onToolComplete("calculator", true, "2", 1);
            listener.onObservation("2");
            return expected;
        });

        SseEmitter emitter = controller.approveStream("APR-1", null);

        assertNotNull(emitter);
        assertNotNull(pendingTask.get());
        pendingTask.get().run();
        verify(agent).resume(eq("run-1"), eq("APR-1"), any(AgentListener.class));
    }

    @Test
    void shouldStreamRejectAndResumeRun() {
        ApprovalRequest rejected = approval(ApprovalStatus.REJECTED);
        AgentRunResult expected = result("final_answer", true);
        when(approvalService.reject("APR-1", "web", null)).thenReturn(rejected);
        when(agent.resume(eq("run-1"), eq("APR-1"), any(AgentListener.class))).thenAnswer(invocation -> {
            AgentListener listener = invocation.getArgument(2);
            listener.onObservation("工具调用被拒绝");
            return expected;
        });

        SseEmitter emitter = controller.rejectStream("APR-1", null);

        assertNotNull(emitter);
        assertNotNull(pendingTask.get());
        pendingTask.get().run();
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

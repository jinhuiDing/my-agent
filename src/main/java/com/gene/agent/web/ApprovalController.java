package com.gene.agent.web;

import com.gene.agent.agent.AgentListener;
import com.gene.agent.agent.AgentRunResult;
import com.gene.agent.agent.ReactAgent;
import com.gene.agent.runtime.RunRegistry;
import com.gene.agent.tool.approval.ApprovalRequest;
import com.gene.agent.tool.approval.ApprovalService;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static com.gene.agent.core.AgentRunTerminationReason.USER_CANCEL;

@RestController
@RequestMapping("/api/approvals")
class ApprovalController {

    private final ApprovalService approvalService;
    private final ReactAgent agent;
    private final TaskExecutor executor;
    private final RunRegistry runRegistry;

    ApprovalController(ApprovalService approvalService, ReactAgent agent,
                       TaskExecutor executor, RunRegistry runRegistry) {
        this.approvalService = approvalService;
        this.agent = agent;
        this.executor = executor;
        this.runRegistry = runRegistry;
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
    ApprovalDecisionResponse approve(@PathVariable String approvalId,
                                     @RequestBody(required = false) ApprovalDecisionRequest request) {
        ApprovalRequest approval = approvalService.approve(
                approvalId, reviewerOf(request), commentOf(request));
        return resume(approval);
    }

    @PostMapping("/{approvalId}/reject")
    ApprovalDecisionResponse reject(@PathVariable String approvalId,
                                    @RequestBody(required = false) ApprovalDecisionRequest request) {
        ApprovalRequest approval = approvalService.reject(
                approvalId, reviewerOf(request), commentOf(request));
        return resume(approval);
    }

    @PostMapping(value = "/{approvalId}/approve/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter approveStream(@PathVariable String approvalId,
                             @RequestBody(required = false) ApprovalDecisionRequest request) {
        ApprovalRequest approval = approvalService.approve(
                approvalId, reviewerOf(request), commentOf(request));
        return stream(approval);
    }

    @PostMapping(value = "/{approvalId}/reject/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter rejectStream(@PathVariable String approvalId,
                            @RequestBody(required = false) ApprovalDecisionRequest request) {
        ApprovalRequest approval = approvalService.reject(
                approvalId, reviewerOf(request), commentOf(request));
        return stream(approval);
    }

    private SseEmitter stream(ApprovalRequest approval) {
        SseEmitter emitter = new SseEmitter(0L);
        SseApprovalListener listener = new SseApprovalListener(
                emitter, runRegistry, approval.runId(), approval.toolName());
        emitter.onCompletion(listener::cancel);
        emitter.onTimeout(listener::cancel);
        emitter.onError(ex -> listener.cancel());

        executor.execute(() -> {
            try {
                if (listener.disconnected) return;
                listener.send("resume_start", Map.of(
                        "runId", approval.runId(),
                        "approvalId", approval.approvalId(),
                        "decision", approval.status().name()));
                ApprovalDecisionResponse response = resume(approval, listener, listener.tracker);
                listener.send("approval_done", response);
            } catch (RuntimeException ex) {
                try {
                    listener.send("error", error(ex));
                } catch (RuntimeException ignored) {
                }
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private ApprovalDecisionResponse resume(ApprovalRequest approval) {
        ToolExecutionTracker tracker = new ToolExecutionTracker(approval.toolName());
        return resume(approval, tracker, tracker);
    }

    private ApprovalDecisionResponse resume(ApprovalRequest approval, AgentListener listener,
                                            ToolExecutionTracker tracker) {
        AgentRunResult result = agent.resume(approval.runId(), approval.approvalId(), listener);
        return new ApprovalDecisionResponse(
                approval,
                tracker.executed,
                tracker.success,
                tracker.output,
                result
        );
    }

    /** 只记录恢复运行时第一段工具调用，避免混入后续 ReAct 步骤。 */
    private static final class ToolExecutionTracker implements AgentListener {

        private final String toolName;
        private boolean firstStepFinished;
        private boolean executed;
        private Boolean success;
        private String output;

        private ToolExecutionTracker(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public void onToolStart(String tool, String input) {
            if (!firstStepFinished && toolName.equals(tool)) {
                executed = true;
            }
        }

        @Override
        public void onToolComplete(String tool, boolean success, String result, long costMs) {
            if (!firstStepFinished && executed && this.success == null && toolName.equals(tool)) {
                this.success = success;
                this.output = result;
            }
        }

        @Override
        public void onObservation(String observation) {
            firstStepFinished = true;
        }
    }

    private static final class SseApprovalListener implements AgentListener {

        private final SseEmitter emitter;
        private final RunRegistry runRegistry;
        private final String runId;
        private final ToolExecutionTracker tracker;
        private volatile boolean disconnected;

        private SseApprovalListener(SseEmitter emitter, RunRegistry runRegistry,
                                    String runId, String toolName) {
            this.emitter = emitter;
            this.runRegistry = runRegistry;
            this.runId = runId;
            this.tracker = new ToolExecutionTracker(toolName);
        }

        @Override
        public void onStepStart(int stepNumber, int maxSteps) {
            send("step", Map.of("step", stepNumber, "maxSteps", maxSteps));
        }

        @Override
        public void onModelDelta(String chunk) {
            send("delta", Map.of("text", chunk));
        }

        @Override
        public void onAction(String tool, String input) {
            send("action", Map.of("tool", tool, "input", input == null ? "" : input));
        }

        @Override
        public void onToolStart(String tool, String input) {
            tracker.onToolStart(tool, input);
            send("tool_start", Map.of("tool", tool, "input", input == null ? "" : input));
        }

        @Override
        public void onToolComplete(String tool, boolean success, String result, long costMs) {
            tracker.onToolComplete(tool, success, result, costMs);
            send("tool_complete", Map.of(
                    "tool", tool, "success", success,
                    "result", result == null ? "" : result, "costMs", costMs));
        }

        @Override
        public void onObservation(String observation) {
            tracker.onObservation(observation);
            send("observation", Map.of("text", observation));
        }

        @Override
        public void onApprovalRequired(ApprovalRequest approval, String actionInput) {
            send("approval_required", Map.of(
                    "runId", approval.runId(),
                    "approvalId", approval.approvalId(),
                    "tool", approval.toolName(),
                    "input", actionInput == null ? "" : actionInput,
                    "reason", approval.reason() == null ? "" : approval.reason()));
        }

        @Override
        public void onNotice(String message) {
            send("notice", Map.of("message", message));
        }

        private void cancel() {
            disconnected = true;
            runRegistry.abort(runId, USER_CANCEL);
        }

        private void send(String event, Object payload) {
            if (disconnected) return;
            try {
                emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException ex) {
                cancel();
                throw new UncheckedIOException(ex);
            } catch (IllegalStateException ex) {
                cancel();
                throw ex;
            }
        }
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

    record ApprovalDecisionResponse(
            ApprovalRequest approval,
            boolean toolExecuted,
            Boolean toolSuccess,
            String toolResult,
            AgentRunResult result
    ) {
    }
}

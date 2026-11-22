package com.gene.agent.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.gene.agent.agent.AgentListener;
import com.gene.agent.agent.AgentRunResult;
import com.gene.agent.agent.ReactAgent;
import com.gene.agent.core.AgentRunTerminationReason;
import com.gene.agent.runtime.AgentRunContext;
import com.gene.agent.runtime.RunRegistry;
import com.gene.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP 入口，方便前端或 curl 调用同一个 agent。
 *
 * 一次性返回：
 * curl -X POST localhost:8080/api/agent/ask -H "Content-Type: application/json" -d "{\"question\":\"美元兑人民币汇率乘以1.13等于多少\"}"
 *
 * 流式返回（SSE，事件: start / step / delta / action / tool_start / tool_complete / observation / notice / done / error）：
 * curl -N -X POST localhost:8080/api/agent/ask/stream -H "Content-Type: application/json" -d "{\"question\":\"...\"}"
 */
@RestController
@RequestMapping("/api")
class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ReactAgent agent;
    private final ToolRegistry tools;
    private final TaskExecutor executor;
    private final RunRegistry runRegistry;

    AgentController(ReactAgent agent, ToolRegistry tools, TaskExecutor executor, RunRegistry runRegistry) {
        this.agent = agent;
        this.tools = tools;
        this.executor = executor;
        this.runRegistry = runRegistry;
    }

    @GetMapping("/tools")
    List<Map<String, String>> listTools() {
        return tools.names().stream().map(name -> Map.of("name", name)).toList();
    }

    @GetMapping("/agent/runs")
    List<RunSnapshot> listRuns() {
        return runRegistry.running().stream().map(AgentController::toRunSnapshot).toList();
    }

    @GetMapping("/agent/runs/{runId}")
    RunSnapshot getRun(@PathVariable String runId) {
        RunRegistry.RunningRun run = runRegistry.find(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "运行不存在或已结束"));
        return toRunSnapshot(run);
    }

    @PostMapping("/agent/runs/{runId}/cancel")
    Map<String, Object> cancelRun(@PathVariable String runId) {
        if (!runRegistry.abort(runId, AgentRunTerminationReason.USER_CANCEL)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "运行不存在或已结束");
        }
        return Map.of("runId", runId, "cancelRequested", true);
    }

    @PostMapping("/agent/ask")
    Map<String, Object> ask(@RequestBody AskRequest request) {
        String question = questionOf(request);
        AgentRunResult result = agent.run(question);
        return toResponse(result);
    }

    @PostMapping(value = "/agent/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter askStream(@RequestBody AskRequest request) {
        String question = questionOf(request);
        // 0 = 不设超时，agent 可能跑好几分钟
        SseEmitter emitter = new SseEmitter(0L);
        SseAgentListener listener = new SseAgentListener(emitter, runRegistry);
        emitter.onCompletion(listener::cancel);
        emitter.onTimeout(listener::cancel);
        emitter.onError(ignored -> listener.cancel());
        executor.execute(() -> {
            try {
                agent.run(question, listener);
            } catch (RuntimeException ex) {
                log.error("流式 agent 运行失败: {}", question, ex);
                sendQuietly(emitter, "error",
                        Map.of("message", ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private static String questionOf(AskRequest request) {
        String question = request == null || request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        return question;
    }

    private static Map<String, Object> toResponse(AgentRunResult result) {
        return Map.of(
                "runId", result.runId(),
                "question", result.question(),
                "solved", result.solved(),
                "stopReason", result.stopReason(),
                "answer", result.answer() == null ? "" : result.answer(),
                "steps", result.steps(),
                "trace", result.describeTrace());
    }

    private static RunSnapshot toRunSnapshot(RunRegistry.RunningRun run) {
        AgentRunContext context = run.context();
        String terminationReason = context.getTerminationReason() == null
                ? null
                : context.getTerminationReason().name();
        return new RunSnapshot(
                context.getRunId(),
                context.getQuestion(),
                context.getStatus().name(),
                context.getCurrentStep(),
                context.getMaxSteps(),
                context.getDurationMs(),
                terminationReason
        );
    }

    private static void sendQuietly(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端已断开，忽略
        }
    }

    @ExceptionHandler(IllegalStateException.class)
    Map<String, String> handleError(IllegalStateException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
    }

    record AskRequest(String question) {
    }

    record RunSnapshot(String runId, String question, String status, int currentStep,
                       int maxSteps, long durationMs, String terminationReason) {
    }

    /** 把 agent 事件转成 SSE；客户端断开时统一走 RunRegistry 的取消路径。 */
    private static final class SseAgentListener implements AgentListener {

        private final SseEmitter emitter;
        private final RunRegistry runRegistry;
        private volatile String runId;
        private volatile boolean disconnected;

        private SseAgentListener(SseEmitter emitter, RunRegistry runRegistry) {
            this.emitter = emitter;
            this.runRegistry = runRegistry;
        }

        @Override
        public void onRunStart(String runId, String question, List<String> tools, int maxSteps) {
            this.runId = runId;
            if (disconnected) {
                // SSE 可能在线程真正启动前就断开，此时拿到 runId 后仍需补发取消。
                cancel();
                return;
            }
            send("start", Map.of("runId", runId, "question", question, "tools", tools, "maxSteps", maxSteps));
        }

        @Override
        public void onStepStart(int step, int maxSteps) {
            send("step", Map.of("step", step, "maxSteps", maxSteps));
        }

        @Override
        public void onModelDelta(String delta) {
            send("delta", Map.of("text", delta));
        }

        @Override
        public void onAction(String tool, String input) {
            send("action", Map.of("tool", tool, "input", input == null ? "" : input));
        }

        @Override
        public void onToolStart(String tool, String input) {
            send("tool_start", Map.of("tool", tool, "input", input == null ? "" : input));
        }

        @Override
        public void onToolComplete(String tool, boolean success, String result, long costMs) {
            send("tool_complete", Map.of(
                    "tool", tool,
                    "success", success,
                    "result", result == null ? "" : result,
                    "costMs", costMs));
        }

        @Override
        public void onObservation(String observation) {
            send("observation", Map.of("text", observation == null ? "" : observation));
        }

        @Override
        public void onNotice(String message) {
            send("notice", Map.of("message", message));
        }

        @Override
        public void onDone(AgentRunResult result) {
            send("done", Map.of(
                    "runId", result.runId(),
                    "solved", result.solved(),
                    "stopReason", result.stopReason(),
                    "answer", result.answer() == null ? "" : result.answer(),
                    "stepCount", result.steps().size(),
                    "trace", result.describeTrace()));
        }

        private void cancel() {
            disconnected = true;
            String currentRunId = runId;
            if (currentRunId != null) {
                runRegistry.abort(currentRunId, AgentRunTerminationReason.USER_CANCEL);
            }
        }

        private void send(String name, Object data) {
            if (disconnected) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException ex) {
                cancel();
                throw new UncheckedIOException(ex);
            } catch (IllegalStateException ex) {
                cancel();
                throw ex;
            }
        }
    }
}

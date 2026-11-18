package com.example.agent.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.example.agent.agent.AgentListener;
import com.example.agent.agent.AgentRunResult;
import com.example.agent.agent.ReactAgent;
import com.example.agent.tool.ToolRegistry;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * 流式返回（SSE，事件: start / step / delta / action / observation / notice / done / error）：
 * curl -N -X POST localhost:8080/api/agent/ask/stream -H "Content-Type: application/json" -d "{\"question\":\"...\"}"
 */
@RestController
@RequestMapping("/api")
class AgentController {

    private final ReactAgent agent;
    private final ToolRegistry tools;
    private final TaskExecutor executor;

    AgentController(ReactAgent agent, ToolRegistry tools, TaskExecutor executor) {
        this.agent = agent;
        this.tools = tools;
        this.executor = executor;
    }

    @GetMapping("/tools")
    List<Map<String, String>> listTools() {
        return tools.names().stream().map(name -> Map.of("name", name)).toList();
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
        executor.execute(() -> {
            try {
                agent.run(question, new SseAgentListener(emitter));
            } catch (RuntimeException ex) {
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
                "question", result.question(),
                "solved", result.solved(),
                "stopReason", result.stopReason(),
                "answer", result.answer() == null ? "" : result.answer(),
                "steps", result.steps(),
                "trace", result.describeTrace());
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

    /** 把 agent 事件转成 SSE；客户端断开时 send 抛异常，直接中止本次运行。 */
    private record SseAgentListener(SseEmitter emitter) implements AgentListener {

        @Override
        public void onRunStart(String question, List<String> tools, int maxSteps) {
            send("start", Map.of("question", question, "tools", tools, "maxSteps", maxSteps));
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
                    "solved", result.solved(),
                    "stopReason", result.stopReason(),
                    "answer", result.answer() == null ? "" : result.answer(),
                    "stepCount", result.steps().size(),
                    "trace", result.describeTrace()));
        }

        private void send(String name, Object data) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }
}

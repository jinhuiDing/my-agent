package com.example.agent.web;

import java.util.List;
import java.util.Map;

import com.example.agent.agent.AgentRunResult;
import com.example.agent.agent.ReactAgent;
import com.example.agent.tool.ToolRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * HTTP 入口，方便前端或 curl 调用同一个 agent。
 * curl -X POST localhost:8080/api/agent/ask -H "Content-Type: application/json" -d "{\"question\":\"美元兑人民币汇率乘以1.13等于多少\"}"
 */
@RestController
@RequestMapping("/api")
class AgentController {

    private final ReactAgent agent;
    private final ToolRegistry tools;

    AgentController(ReactAgent agent, ToolRegistry tools) {
        this.agent = agent;
        this.tools = tools;
    }

    @GetMapping("/tools")
    List<Map<String, String>> listTools() {
        return tools.names().stream().map(name -> Map.of("name", name)).toList();
    }

    @PostMapping("/agent/ask")
    Map<String, Object> ask(@RequestBody AskRequest request) {
        String question = request == null || request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        AgentRunResult result = agent.run(question);
        return Map.of(
                "question", result.question(),
                "solved", result.solved(),
                "stopReason", result.stopReason(),
                "answer", result.answer() == null ? "" : result.answer(),
                "steps", result.steps(),
                "trace", result.describeTrace());
    }

    @ExceptionHandler(IllegalStateException.class)
    Map<String, String> handleError(IllegalStateException ex) {
        return Map.of("error", ex.getMessage() == null ? ex.toString() : ex.getMessage());
    }

    record AskRequest(String question) {
    }
}

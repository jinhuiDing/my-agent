package com.gene.agent.agent;

import static org.junit.jupiter.api.Assertions.*;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gene.agent.config.AgentProperties;
import com.gene.agent.llm.LlmClient;
import com.gene.agent.llm.LlmRequest;
import com.gene.agent.output.AgentOutputParser;
import com.gene.agent.tool.Tool;
import com.gene.agent.tool.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReactAgentTest {

    private LlmClient llm;
    private AgentListener listener;
    private ReactAgent agent;
    private Tool tool;
    private ObjectNode toolInput;
    @BeforeEach
    void setUp() {
        // Mock 外部依赖
        llm = mock(LlmClient.class);
        listener = mock(AgentListener.class);
        tool = mock(Tool.class);


        when(tool.name()).thenReturn("test_tool");
        when(tool.description()).thenReturn("测试工具");
        when(tool.execute(any(JsonNode.class))).thenReturn("工具执行结果");

        // 这里使用包含 mockTool 的注册表
        ToolRegistry tools = new ToolRegistry(List.of(tool));



        ReActPrompt prompt = new ReActPrompt(tools);
        AgentOutputParser parser = new AgentOutputParser(new ObjectMapper());
        toolInput = new ObjectMapper().createObjectNode().put("param", "测试参数");

        AgentProperties properties = new AgentProperties(
                "http://localhost",
                "test-api-key",
                "test-model",
                0.0,
                3,
                1000,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                false
        );

        agent = new ReactAgent(
                llm,
                prompt,
                parser,
                tools,
                properties
        );
    }

    @Test
    void shouldReturnFinalAnswerWithoutCallingTool() {
        // Arrange
        String question = "什么是 Java？";

        String modelResponse = """
                {"type":"final","answer":"Java 是一种面向对象的编程语言。"}
                """;

        when(llm.completeStream(
                any(LlmRequest.class),any()
        )).thenReturn(modelResponse);

        // Act
        AgentRunResult result = agent.run(question, listener);

        // Assert：验证结果
        assertNotNull(result);
        assertEquals(question, result.question());
        assertEquals(
                "Java 是一种面向对象的编程语言。",
                result.answer()
        );
        assertTrue(result.solved());
        assertEquals("final_answer", result.stopReason());

        // 直接回答，不应该产生工具执行历史
        assertTrue(result.steps().isEmpty());

        // LLM 只调用一次
        verify(llm, times(1))
                .completeStream(any(LlmRequest.class), any());

        // 验证生命周期事件
        verify(listener).onRunStart(question, List.of("test_tool"), 3);
        verify(listener).onModelComplete(modelResponse);
        verify(listener).onDone(result);

        // 不应该调用工具
        verify(listener, never()).onAction(anyString(), anyString());
        verify(listener, never()).onObservation(anyString());

        // 不应该报告异常
        verify(listener, never()).onError(any());
    }


    @Test
    void shouldExecuteToolAndReturnFinalAnswer() {
        // Arrange
        String question = "帮我执行测试工具";

        String actionResponse = """
            {"type":"action","thought":"我需要调用测试工具","action":{"name":"test_tool","input":{"param":"测试参数"}}}
            """;

        String finalResponse = """
            {"type":"final","answer":"工具执行成功，结果是工具执行结果。"}
            """;

        when(llm.completeStream(any(), any()))
                .thenReturn(actionResponse)
                .thenReturn(finalResponse);

        // Act
        AgentRunResult result = agent.run(question, listener);

        // Assert：最终结果
        assertNotNull(result);
        assertEquals(question, result.question());
        assertEquals("工具执行成功，结果是工具执行结果。", result.answer());
        assertTrue(result.solved());
        assertEquals("final_answer", result.stopReason());

        // 验证工具只执行一次
        verify(tool, times(1)).execute(toolInput);

        // 验证 LLM 调用了两次
        verify(llm, times(2))
                .completeStream(any(), any());

        // 验证历史记录
        assertEquals(1, result.steps().size());

        Step step = result.steps().get(0);
        assertEquals("test_tool", step.action());
        assertEquals(toolInput.toPrettyString(), step.actionInput());
        assertEquals("工具执行结果", step.observation());

        // 验证事件
        verify(listener).onAction("test_tool", toolInput.toPrettyString());
        verify(listener).onObservation("工具执行结果");
        verify(listener).onDone(result);
        verify(listener, never()).onError(any());
    }

    @Test
    void shouldHandleUnknownToolAndContinue() {
        // Arrange
        String question = "帮我查询数据";

        String actionResponse = """
            {"type":"action","thought":"我需要查询数据","action":{"name":"unknown_tool","input":{"param":"测试参数"}}}
            """;

        String finalResponse = """
            {"type":"final","answer":"当前无法查询该数据。"}
            """;

        when(llm.completeStream(any(), any()))
                .thenReturn(actionResponse)
                .thenReturn(finalResponse);

        // Act
        AgentRunResult result = agent.run(question, listener);

        // Assert
        assertNotNull(result);
        assertEquals("当前无法查询该数据。", result.answer());
        assertTrue(result.solved());
        assertEquals("final_answer", result.stopReason());

        // 未知工具也应该形成一条执行历史
        assertEquals(1, result.steps().size());

        Step step = result.steps().get(0);
        assertEquals("unknown_tool", step.action());
        assertEquals(toolInput.toPrettyString(), step.actionInput());

        // 未知工具只发 onNotice，没有执行结果，observation 为 null
        assertNull(step.observation());

        // LLM 第二次调用成功给出答案
        verify(llm, times(2))
                .completeStream(any(), any());

        // 不应该执行任何实际工具
        verify(tool, never()).execute(any(JsonNode.class));

        // 应该通知监听器
        verify(listener).onNotice(contains("unknown_tool"));
        verify(listener).onObservation(isNull());
        verify(listener).onDone(result);

        // 未知工具不是 Runtime 异常
        verify(listener, never()).onError(any());
    }

}

package com.gene.agent.agent;

import static org.junit.jupiter.api.Assertions.*;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gene.agent.config.AgentProperties;
import com.gene.agent.core.AgentRunTerminationReason;
import com.gene.agent.llm.LlmClient;
import com.gene.agent.llm.LlmRequest;
import com.gene.agent.output.AgentOutputParser;
import com.gene.agent.runtime.AgentRunStore;
import com.gene.agent.runtime.InMemoryAgentRunStore;
import com.gene.agent.runtime.RunRegistry;
import com.gene.agent.runtime.Step;
import com.gene.agent.tool.Tool;
import com.gene.agent.tool.ToolInputValidator;
import com.gene.agent.tool.ToolRegistry;
import com.gene.agent.tool.approval.ApprovalRequest;
import com.gene.agent.tool.approval.ApprovalService;
import com.gene.agent.tool.approval.InMemoryApprovalStore;
import com.gene.agent.tool.policy.CompositeToolExecutionPolicy;
import com.gene.agent.tool.policy.ToolExecutionDecision;
import com.gene.agent.tool.policy.ToolExecutionPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReactAgentTest {

    private LlmClient llm;
    private AgentListener listener;
    private ReactAgent agent;
    private Tool tool;
    private ObjectNode toolInput;
    private RunRegistry runRegistry;
    private ToolRegistry tools;
    private ReActPrompt prompt;
    private AgentOutputParser parser;
    private ToolInputValidator toolInputValidator;
    private ApprovalService approvalService;
    private AgentRunStore agentRunStore;

    @BeforeEach
    void setUp() {
        // Mock 外部依赖
        llm = mock(LlmClient.class);
        listener = mock(AgentListener.class);
        tool = mock(Tool.class);


        when(tool.name()).thenReturn("test_tool");
        when(tool.description()).thenReturn("测试工具");
        when(tool.inputSchema()).thenReturn(
                new ObjectMapper().createObjectNode().put("type", "object")
        );
        when(tool.execute(any(JsonNode.class))).thenReturn("工具执行结果");

        // 这里使用包含 mockTool 的注册表
        tools = new ToolRegistry(List.of(tool));
        prompt = new ReActPrompt(tools);
        parser = new AgentOutputParser(new ObjectMapper());
        toolInput = new ObjectMapper().createObjectNode().put("param", "测试参数");
        toolInputValidator = mock(ToolInputValidator.class);
        runRegistry = new RunRegistry();
        approvalService = new ApprovalService(new InMemoryApprovalStore());
        agentRunStore = new InMemoryAgentRunStore();
        agent = createAgent(Duration.ofSeconds(30));
    }

    private ReactAgent createAgent(Duration runTimeout) {
        return createAgent(runTimeout, Duration.ofSeconds(5));
    }

    private ReactAgent createAgent(Duration runTimeout, Duration toolTimeout) {
        return createAgent(runTimeout, toolTimeout,
                (tool, input) -> ToolExecutionDecision.allow());
    }

    private ReactAgent createAgent(Duration runTimeout, Duration toolTimeout,
                                   ToolExecutionPolicy executionPolicy) {
        AgentProperties properties = new AgentProperties(
                "http://localhost",
                "test-api-key",
                "test-model",
                0.0,
                3,
                1000,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                toolTimeout,
                runTimeout,
                false
        );
        ReactAgent reactAgent = new ReactAgent(
                llm,
                new CompositeToolExecutionPolicy(List.of(executionPolicy)),
                prompt,
                parser,
                tools,
                properties,
                toolInputValidator,
                runRegistry,
                approvalService
        );
        ReflectionTestUtils.setField(reactAgent, "agentRunStore", agentRunStore);
        return reactAgent;
    }

    @AfterEach
    void tearDown() {
        agent.shutdown();
        runRegistry.shutdown();
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
        verify(listener).onRunStart(result.runId(), question, List.of("test_tool"), 3);
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
        // 通用 Schema 不锁工具名，未注册工具能进入 tool == null 兜底分支，
        // 形成一条执行历史，observation 为"未找到工具"，模型据此纠正后给出最终答案。
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

        AgentRunResult result = agent.run(question, listener);

        assertNotNull(result);
        assertEquals("当前无法查询该数据。", result.answer());
        assertTrue(result.solved());
        assertEquals("final_answer", result.stopReason());

        // 未知工具形成一条执行历史
        assertEquals(1, result.steps().size());

        Step step = result.steps().get(0);
        assertEquals("unknown_tool", step.action());
        assertEquals(toolInput.toPrettyString(), step.actionInput());
        assertEquals("未找到工具：unknown_tool", step.observation());

        // 两次模型调用：第一次选未知工具，第二次给出最终答案
        verify(llm, times(2)).completeStream(any(), any());

        // 未真正执行任何工具
        verify(tool, never()).execute(any(JsonNode.class));

        // 兜底分支：先 onNotice，再把"未找到工具"写入 observation
        verify(listener).onNotice(contains("unknown_tool"));
        verify(listener).onObservation("未找到工具：unknown_tool");
        verify(listener).onDone(result);
        verify(listener, never()).onError(any());
    }

    @Test
    void shouldInterruptToolAndReturnTimeoutObservation() throws Exception {
        ReactAgent toolTimeoutAgent = createAgent(Duration.ofSeconds(30), Duration.ofMillis(100));
        CountDownLatch toolInterrupted = new CountDownLatch(1);
        when(tool.execute(any(JsonNode.class))).thenAnswer(invocation -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                toolInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new RuntimeException("工具调用被中断", ex);
            }
            return "不应返回";
        });
        when(llm.completeStream(any(), any()))
                .thenReturn("{\"type\":\"action\",\"thought\":\"调用工具\",\"action\":{\"name\":\"test_tool\",\"input\":{\"param\":\"测试参数\"}}}")
                .thenReturn("{\"type\":\"final\",\"answer\":\"工具超时后改用已有信息回答。\"}");

        try {
            AgentRunResult result = toolTimeoutAgent.run("测试工具超时", listener);

            assertTrue(result.solved());
            assertEquals("final_answer", result.stopReason());
            assertEquals("工具超时后改用已有信息回答。", result.answer());
            assertEquals(1, result.steps().size());
            assertTrue(result.steps().get(0).observation().contains("工具执行超时"));
            assertTrue(toolInterrupted.await(2, TimeUnit.SECONDS));
            verify(listener).onToolComplete(eq("test_tool"), eq(false), contains("工具执行超时"), anyLong());
            verify(llm, times(2)).completeStream(any(), any());
        } finally {
            toolTimeoutAgent.shutdown();
        }
    }

    @Test
    void shouldInterruptModelCallWhenUserCancels() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        when(llm.completeStream(any(), any())).thenAnswer(invocation -> {
            modelStarted.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("模型调用被中断", ex);
            }
            return "{\"type\":\"final\",\"answer\":\"不应返回\"}";
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AgentRunResult> future = executor.submit(() -> agent.run("长期运行的任务", listener));
            assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
            assertEquals(1, runRegistry.running().size());

            String runId = runRegistry.running().get(0).context().getRunId();
            assertTrue(runRegistry.abort(runId, AgentRunTerminationReason.USER_CANCEL));

            AgentRunResult result = future.get(3, TimeUnit.SECONDS);
            assertEquals(runId, result.runId());
            assertFalse(result.solved());
            assertEquals("user_cancel", result.stopReason());
            assertEquals("运行已被取消。", result.answer());
            assertTrue(runRegistry.find(runId).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldEmitStructuredApprovalEventAndSaveRun() {
        ReactAgent approvalAgent = createAgent(
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                (tool, input) -> ToolExecutionDecision.requireApproval("需要人工审批")
        );
        when(llm.completeStream(any(), any())).thenReturn("""
                {"type":"action","thought":"调用测试工具","action":{"name":"test_tool","input":{"param":"测试参数"}}}
                """);

        try {
            AgentRunResult result = approvalAgent.run("等待审批", listener);

            assertFalse(result.solved());
            assertEquals("waiting_approval", result.stopReason());
            assertEquals(1, approvalService.getPendingApprovals().size());
            ApprovalRequest approval = approvalService.getPendingApprovals().get(0);
            assertEquals(result.runId(), approval.runId());
            assertNotNull(agentRunStore.findById(result.runId()));
            verify(listener).onApprovalRequired(approval, toolInput.toPrettyString());
            verify(tool, never()).execute(any(JsonNode.class));
        } finally {
            approvalAgent.shutdown();
        }
    }

    @Test
    void shouldInterruptModelCallWhenRunTimesOut() throws Exception {
        ReactAgent timeoutAgent = createAgent(Duration.ofMillis(100));
        CountDownLatch modelStarted = new CountDownLatch(1);
        when(llm.completeStream(any(), any())).thenAnswer(invocation -> {
            modelStarted.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("模型调用被中断", ex);
            }
            return "{\"type\":\"final\",\"answer\":\"不应返回\"}";
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AgentRunResult> future = executor.submit(() -> timeoutAgent.run("会超时的任务", listener));
            assertTrue(modelStarted.await(2, TimeUnit.SECONDS));

            AgentRunResult result = future.get(3, TimeUnit.SECONDS);
            assertNotNull(result.runId());
            assertFalse(result.solved());
            assertEquals("timeout", result.stopReason());
            assertEquals("运行已超时。", result.answer());
            assertTrue(runRegistry.find(result.runId()).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

}

package com.gene.agent.web;

import com.gene.agent.agent.AgentListener;
import com.gene.agent.agent.ReactAgent;
import com.gene.agent.core.AgentRunTerminationReason;
import com.gene.agent.runtime.AgentRunContext;
import com.gene.agent.runtime.RunRegistry;
import com.gene.agent.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentControllerTest {

    private ReactAgent agent;
    private RunRegistry runRegistry;
    private AtomicReference<Runnable> pendingTask;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        agent = mock(ReactAgent.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        runRegistry = mock(RunRegistry.class);
        pendingTask = new AtomicReference<>();
        controller = new AgentController(agent, tools, pendingTask::set, runRegistry);
    }

    @Test
    void shouldQueryAndCancelRunningRun() {
        AgentRunContext context = new AgentRunContext("run-1", "测试问题", 3);
        context.start();
        RunRegistry.RunningRun runningRun = mock(RunRegistry.RunningRun.class);
        when(runningRun.context()).thenReturn(context);
        when(runRegistry.find("run-1")).thenReturn(Optional.of(runningRun));
        when(runRegistry.running()).thenReturn(List.of(runningRun));
        when(runRegistry.abort("run-1", AgentRunTerminationReason.USER_CANCEL)).thenReturn(true);

        AgentController.RunSnapshot snapshot = controller.getRun("run-1");
        assertEquals("run-1", snapshot.runId());
        assertEquals("测试问题", snapshot.question());
        assertEquals("RUNNING", snapshot.status());
        assertEquals(1, controller.listRuns().size());

        Map<String, Object> response = controller.cancelRun("run-1");
        assertEquals("run-1", response.get("runId"));
        assertEquals(true, response.get("cancelRequested"));
        verify(runRegistry).abort("run-1", AgentRunTerminationReason.USER_CANCEL);
    }

    @Test
    void shouldReturnNotFoundForUnknownRun() {
        when(runRegistry.find("missing")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.getRun("missing")
        );
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void shouldCancelRunWhenSseDisconnectsBeforeWorkerStarts() {
        doAnswer(invocation -> {
            AgentListener listener = invocation.getArgument(1);
            listener.onRunStart("run-1", "流式问题", List.of(), 3);
            return null;
        }).when(agent).run(eq("流式问题"), any(AgentListener.class));

        SseEmitter emitter = controller.askStream(new AgentController.AskRequest("流式问题"));
        assertNotNull(pendingTask.get());

        // 模拟工作线程执行前客户端已经断开。
        emitter.complete();
        pendingTask.get().run();

        verify(runRegistry).abort("run-1", AgentRunTerminationReason.USER_CANCEL);
    }
}

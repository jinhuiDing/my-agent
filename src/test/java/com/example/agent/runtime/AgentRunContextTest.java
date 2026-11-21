package com.example.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunContextTest {

    @Test
    void shouldStartFromCreated() {
        AgentRunContext context = new AgentRunContext("test", 5);

        assertEquals(AgentRunStatus.CREATED, context.getStatus());

        context.start();

        assertEquals(AgentRunStatus.RUNNING, context.getStatus());
    }

    @Test
    void shouldCompleteSuccessfully() {
        AgentRunContext context = new AgentRunContext("test", 5);

        context.start();
        context.complete();

        assertEquals(AgentRunStatus.COMPLETED, context.getStatus());
    }

    @Test
    void shouldFail() {
        AgentRunContext context = new AgentRunContext("test", 5);

        context.start();
        context.fail();

        assertEquals(AgentRunStatus.FAILED, context.getStatus());
    }

    @Test
    void shouldFinishByLimit() {
        AgentRunContext context = new AgentRunContext("test", 5);

        context.start();
        context.finishByLimit();

        assertEquals(AgentRunStatus.MAX_STEPS_REACHED, context.getStatus());
    }

    @Test
    void shouldRejectStartingTwice() {
        AgentRunContext context = new AgentRunContext("test", 5);

        context.start();

        assertThrows(IllegalStateException.class, context::start);
    }

    @Test
    void shouldRejectCompletingBeforeStart() {
        AgentRunContext context = new AgentRunContext("test", 5);

        assertThrows(IllegalStateException.class, context::complete);
    }
}

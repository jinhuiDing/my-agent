package com.gene.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryAgentRunStore implements AgentRunStore {

    private final Map<String, AgentRunContext> runs =
            new ConcurrentHashMap<>();

    @Override
    public void save(AgentRunContext context) {
        runs.put(context.getRunId(), context);
    }

    @Override
    public AgentRunContext findById(String runId) {
        return runs.get(runId);
    }

    @Override
    public void remove(String runId) {
        runs.remove(runId);
    }
}
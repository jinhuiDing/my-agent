package com.gene.agent.runtime;

public interface AgentRunStore {

    void save(AgentRunContext context);

    AgentRunContext findById(String runId);

    void remove(String runId);
}
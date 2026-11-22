package com.gene.agent.core;

public enum AgentRunTerminationReason {

    FINAL_ANSWER,

    STEP_LIMIT_REACHED,

    USER_CANCEL,

    TIMEOUT,

    LLM_ERROR,

    PARSE_ERROR,

    TOOL_ERROR,

    INTERNAL_ERROR
}

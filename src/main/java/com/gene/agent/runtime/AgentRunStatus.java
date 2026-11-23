package com.gene.agent.runtime;

public enum AgentRunStatus {

    CREATED,

    RUNNING,

    COMPLETED,

    FAILED,

    MAX_STEPS_REACHED,
//    等待审批
    WAITING_APPROVAL,
//    用户取消
    CANCELLED
}

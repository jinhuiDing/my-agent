package com.gene.agent.exception;

public class AgentOutputParseException extends RuntimeException {

    public AgentOutputParseException(String message) {
        super(message);
    }

    public AgentOutputParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

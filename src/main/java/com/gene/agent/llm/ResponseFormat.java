package com.gene.agent.llm;

public record ResponseFormat(
        Type type,
        String name,
        String schema
) {

    public enum Type {
        TEXT,
        JSON_SCHEMA
    }

    public static ResponseFormat text() {
        return new ResponseFormat(Type.TEXT, null, null);
    }

    public static ResponseFormat jsonSchema(
            String name,
            String schema) {

        return new ResponseFormat(
                Type.JSON_SCHEMA,
                name,
                schema
        );
    }
}

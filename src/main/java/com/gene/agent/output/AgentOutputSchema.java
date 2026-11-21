package com.gene.agent.output;

public class AgentOutputSchema {
    private AgentOutputSchema() {
    }

    public static final String SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "oneOf": [
                {
                  "type": "object",
                  "properties": {
                    "type": {
                      "type": "string",
                      "const": "action"
                    },
                    "thought": {
                      "type": ["string", "null"]
                    },
                    "action": {
                      "type": "object",
                      "properties": {
                        "name": {
                          "type": "string",
                          "minLength": 1
                        },
                        "input": {
                          "type": "object",
                          "additionalProperties": true
                        }
                      },
                      "required": ["name", "input"],
                      "additionalProperties": false
                    }
                  },
                  "required": ["type", "thought", "action"],
                  "additionalProperties": false
                },
                {
                  "type": "object",
                  "properties": {
                    "type": {
                      "type": "string",
                      "const": "final"
                    },
                    "answer": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "required": ["type", "answer"],
                  "additionalProperties": false
                }
              ]
            }
            """;
}

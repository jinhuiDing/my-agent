package com.gene.agent.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gene.agent.tool.Tool;

import java.util.List;

public class AgentOutputSchema {
    private AgentOutputSchema() {
    }
    public static String build(List<Tool> tools) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");

        ArrayNode rootOneOf = root.putArray("oneOf");

        // Action 输出
        ObjectNode actionOutput = rootOneOf.addObject();
        actionOutput.put("type", "object");

        ObjectNode actionProperties = actionOutput.putObject("properties");

        actionProperties.putObject("type")
                .put("type", "string")
                .put("const", "action");

        actionProperties.putObject("thought")
                .put("type", "string");

        ObjectNode actionSchema = actionProperties.putObject("action");

        // Action 根据工具动态生成
        ArrayNode actionOneOf = actionSchema.putArray("oneOf");

        for (Tool tool : tools) {
            ObjectNode variant = actionOneOf.addObject();
            variant.put("type", "object");

            ObjectNode properties = variant.putObject("properties");

            properties.putObject("name")
                    .put("type", "string")
                    .put("const", tool.name());

            JsonNode inputSchema = tool.inputSchema();
            if (inputSchema == null || !inputSchema.isObject()) {
                throw new IllegalArgumentException(
                        "Tool " + tool.name() + " 的 inputSchema 必须是 JSON Object"
                );
            }

            properties.set("input", inputSchema.deepCopy());

            variant.putArray("required")
                    .add("name")
                    .add("input");

            variant.put("additionalProperties", false);
        }

        actionOutput.putArray("required")
                .add("type")
                .add("thought")
                .add("action");

        actionOutput.put("additionalProperties", false);

        // Final 输出
        ObjectNode finalOutput = rootOneOf.addObject();
        finalOutput.put("type", "object");

        ObjectNode finalProperties = finalOutput.putObject("properties");

        finalProperties.putObject("type")
                .put("type", "string")
                .put("const", "final");

        finalProperties.putObject("answer")
                .put("type", "string")
                .put("minLength", 1);

        finalOutput.putArray("required")
                .add("type")
                .add("answer");

        finalOutput.put("additionalProperties", false);

        return root.toString();
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

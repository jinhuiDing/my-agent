package com.gene.agent.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class DashScopeRequestAdapter {

    private final ObjectMapper objectMapper;

    public DashScopeRequestAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode toRequestBody(String model, LlmRequest request) {
        ObjectNode body = objectMapper.createObjectNode();

        body.put("model", model);

        ArrayNode messages = body.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", request.systemPrompt());

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", request.userPrompt());

        appendResponseFormat(body, request.responseFormat());

        return body;
    }

    private void appendResponseFormat(
            ObjectNode body,
            ResponseFormat responseFormat) {

        if (responseFormat == null
                || responseFormat.type() == ResponseFormat.Type.TEXT) {
            return;
        }

        if (responseFormat.type() != ResponseFormat.Type.JSON_SCHEMA) {
            throw new IllegalArgumentException(
                    "不支持的响应格式：" + responseFormat.type()
            );
        }

        try {
            JsonNode schemaNode =
                    objectMapper.readTree(responseFormat.schema());

            ObjectNode responseFormatNode =
                    body.putObject("response_format");

            responseFormatNode.put("type", "json_schema");

            ObjectNode jsonSchema =
                    responseFormatNode.putObject("json_schema");

            jsonSchema.put("name", responseFormat.name());
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", schemaNode);

        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "JSON Schema 格式不正确", e
            );
        }
    }
}

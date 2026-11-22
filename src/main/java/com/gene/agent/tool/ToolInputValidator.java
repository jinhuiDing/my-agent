package com.gene.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;
@Component
public class ToolInputValidator {

    private final JsonSchemaFactory schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public String validate(Tool tool, JsonNode input) {

        if (input == null || !input.isObject()) {
            return "工具参数校验失败：input 必须是 JSON 对象";
        }

        JsonNode inputSchema = tool.inputSchema();

        if (inputSchema == null || !inputSchema.isObject()) {
            return "工具配置错误："
                    + tool.name()
                    + " 的 inputSchema 无效";
        }

        try {
            JsonSchema schema = schemaFactory.getSchema(inputSchema);

            Set<ValidationMessage> errors = schema.validate(input);

            if (errors.isEmpty()) {
                return null;
            }

            String message = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));

            return "工具参数校验失败：" + message;

        } catch (RuntimeException ex) {
            return "工具 Schema 校验异常："
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }
}

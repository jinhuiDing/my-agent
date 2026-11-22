package com.gene.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 计算器：给模型一个算式，返回结果。
 * 模型自己算数容易出错，所以这一步的观察值必须是确定性的。
 */
@Component
public class CalculatorTool implements Tool {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();

        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode expression = properties.putObject("expression");
        expression.put("type", "string");
        expression.put("minLength", 1);
        expression.put("description", "仅包含算术表达式，例如 (237 + 45) * 1.13；不要带单位、等号或解释文字");

        schema.putArray("required").add("expression");
        schema.put("additionalProperties", false);

        return schema;
    }

    @Override
    public String description() {
        return "用于精确算术计算。输入 JSON 对象，必填字段 expression 是仅含算式的字符串，不要带单位、等号或解释文字。"
                + "支持 + - * / % ^、括号、科学计数法，以及 sqrt/cbrt/abs/round/floor/ceil/ln/log10/log/exp/"
                + "sin/cos/tan/pow/min/max 和常量 pi、e。"
                + "log(x) 以 10 为底，log(base,x) 以 base 为底；三角函数使用弧度。"
                + "例子: {\"expression\":\"(237 + 45) * 1.13\"} 或 {\"expression\":\"sqrt(2) ^ 4\"}";
    }

    @Override
    public String execute(JsonNode input) {
        String expression = input == null
                ? ""
                : stripWrappers(input.path("expression").asText("").trim());
        if (expression.isBlank()) {
            return "错误: calculator 需要 expression 字段，例如 {\"expression\":\"237 + 45 * 2\"}";
        }
        try {
            double value = ExpressionEvaluator.eval(expression);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return "错误: " + expression + " 的结果不是有限数字";
            }
            return format(expression, value);
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            return "错误: 算式无法解析 -> " + message;
        }
    }

    /** 模型有时会写成 3*4= 或者 "12 + 30"，把干扰字符去掉。 */
    private String stripWrappers(String raw) {
        String cleaned = raw.replaceAll("^\"+|\"+$", "").replaceAll("^'+|'+$", "");
        int equals = cleaned.lastIndexOf('=');
        if (equals > 0 && equals == cleaned.length() - 1) {
            cleaned = cleaned.substring(0, equals).trim();
        }
        return cleaned;
    }

    private String format(String expression, double value) {
        if (Math.abs(value) < 1e15 && value == Math.rint(value)) {
            return expression + " = " + (long) value;
        }
        return expression + " = " + String.format("%.10g", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}

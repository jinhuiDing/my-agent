package com.example.agent.tool;

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
    public String description() {
        return "计算一个算术表达式，输入只要算式本身，不要带单位或解释文字。"
                + "支持 + - * / % ^ 括号，以及 sqrt/abs/round/floor/ceil/ln/log/exp/sin/cos/tan/pow/min/max 和常量 pi、e。"
                + "例子: calculator[ (237 + 45) * 1.13 ] 或 calculator[ sqrt(2) ^ 4 ]";
    }

    @Override
    public String execute(String input) {
        if (input == null || input.isBlank()) {
            return "错误: calculator 需要一个算式，例如 237 + 45 * 2";
        }
        String expression = stripWrappers(input.trim());
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

package com.example.agent.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

/**
 * 把模型吐出来的自由文本，解析成结构化的一步。
 * 这是 ReAct 最容易脏的地方：真实模型会写成 **Action:** 、Action：（中文冒号）、search[关键词] 等等。
 */
@Component
class ReActParser {

    private static final Pattern THOUGHT = Pattern.compile("(?im)^\\s*Thought\\s*:\\s*(.+?)(?=\\s*\\n\\s*(?:Action|Final\\s*Answer)\\s*:|$)", Pattern.DOTALL);
    private static final Pattern ACTION = Pattern.compile("(?im)^\\s*Action\\s*:\\s*(.+)$");
    private static final Pattern ACTION_INPUT = Pattern.compile("(?im)^\\s*Action\\s*Input\\s*:\\s*(.*)", Pattern.DOTALL);
    private static final Pattern FINAL_ANSWER = Pattern.compile("(?im)^\\s*Final\\s*Answer\\s*:\\s*(.*)", Pattern.DOTALL);

    private final ToolRegistry tools;
    private final Pattern toolCallFallback;

    ReActParser(ToolRegistry tools) {
        this.tools = tools;
        // 兜底：模型直接写成 search[ 杭州 地铁 ] 这种一行式调用
        String names = String.join("|", tools.names().stream().map(Pattern::quote).toList());
        this.toolCallFallback = Pattern.compile("(?i)\\b(" + names + ")\\s*\\[\\s*(.*?)\\s*]", Pattern.DOTALL);
    }

    ParsedStep parse(String rawModelText) {
        String text = normalize(rawModelText);

        String finalAnswer = firstGroup(FINAL_ANSWER, text);
        String thought = clean(firstGroup(THOUGHT, text));
        String action = clean(firstGroup(ACTION, text));
        String actionInput = clean(firstGroup(ACTION_INPUT, text));

        if (finalAnswer != null && !finalAnswer.isBlank()) {
            // 只有 Final Answer 没有 Action 才算结束；两者都有说明模型想继续用工具
            if (action == null || action.isBlank()) {
                return new ParsedStep(thought, null, null, trim(finalAnswer), text);
            }
        }

        if (action == null || action.isBlank()) {
            Matcher matcher = toolCallFallback.matcher(text);
            if (matcher.find()) {
                return new ParsedStep(thought, matcher.group(1), matcher.group(2), null, text);
            }
            return new ParsedStep(thought, null, null, null, text);
        }
        return new ParsedStep(thought, action, actionInput == null ? "" : actionInput, null, text);
    }

    /** 统一成可以正则匹配的形态：去粗体、中文冒号换英文冒号、去掉代码围栏。 */
    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("：", ":").replace("**", "").replace("`", "");
        text = text.replaceAll("(?m)^\\s*(Thought|Action Input|Action|Final Answer)\\s*:", "$1:");
        return text.replace("\r\n", "\n").trim();
    }

    private String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        return value.isEmpty() ? null : value;
    }

    /** 模型有时会把下一轮的模板也抄进来，只留第一行有效内容。 */
    private String clean(String value) {
        if (value == null) {
            return null;
        }
        String firstLine = value.split("\n", 2)[0].trim();
        return firstLine.isEmpty() ? null : firstLine;
    }

    private String trim(String value) {
        return value.replaceAll("\\s+\n", "\n").trim();
    }

    /** 工具名对不上时，把可用清单还给模型当观察值，它下一轮通常能自我纠正。 */
    String unknownToolObservation(String action) {
        return "错误: 没有名为 " + action + " 的工具。可用工具只有 "
                + String.join("、", tools.names()) + "，请重新按格式输出 Action 和 Action Input。";
    }

    record ParsedStep(String thought, String action, String actionInput, String finalAnswer, String raw) {

        boolean isFinal() {
            return finalAnswer != null;
        }

        boolean hasAction() {
            return action != null && !action.isBlank();
        }
    }
}

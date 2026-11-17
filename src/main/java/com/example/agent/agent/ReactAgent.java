package com.example.agent.agent;

import java.util.ArrayList;
import java.util.List;

import com.example.agent.agent.ReActParser.ParsedStep;
import com.example.agent.config.AgentProperties;
import com.example.agent.llm.LlmClient;
import com.example.agent.tool.Tool;
import com.example.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 手写 ReAct 的心脏：一个 while 循环。
 *
 * <pre>
 *   拼 prompt（问题 + 历史） -> 问模型下一步 -> 解析出 Action
 *   -> 执行工具拿到 Observation -> 追加进历史 -> 直到 Final Answer 或步数用尽
 * </pre>
 *
 * 没有记忆、没有并行、没有反思，第一版就这样。
 */
@Service
public class ReactAgent {

    private static final Logger log = LoggerFactory.getLogger(ReactAgent.class);

    private final LlmClient llm;
    private final ReActPrompt prompt;
    private final ReActParser parser;
    private final ToolRegistry tools;
    private final AgentProperties properties;

    public ReactAgent(LlmClient llm, ReActPrompt prompt, ReActParser parser,
                      ToolRegistry tools, AgentProperties properties) {
        this.llm = llm;
        this.prompt = prompt;
        this.parser = parser;
        this.tools = tools;
        this.properties = properties;
    }

    public AgentRunResult run(String question) {
        List<Step> history = new ArrayList<>();
        log.info("""
                ============== 新任务 ==============
                问题: {}
                工具: {}""", question.trim(), tools.names());

        for (int step = 1; step <= properties.maxSteps(); step++) {
            String system = prompt.system();
            String user = prompt.user(question, history, step);
            String raw = llm.complete(system, user);
            ParsedStep parsed = parser.parse(raw);

            log.info("""
                    ---------- 第 {}/{} 步 ----------
                    模型原文:
                    {}""", step, properties.maxSteps(), raw);

            if (parsed.isFinal()) {
                log.info("[完成] 拿到 Final Answer，共 {} 步", step);
                return new AgentRunResult(question, parsed.finalAnswer(), history, true, "final_answer");
            }

            String observation;
            String actionName = null;
            String actionInput = parsed.actionInput();
            if (!parsed.hasAction()) {
                observation = "格式错误: 你的回复里既没有 Action/Action Input，也没有 Final Answer。"
                        + "请重新严格按格式 A 或格式 B 输出，只输出一步。";
            } else {
                Tool tool = tools.find(parsed.action()).orElse(null);
                if (tool == null) {
                    observation = parser.unknownToolObservation(parsed.action());
                } else {
                    actionName = tool.name();
                    log.info("[执行] {} <- {}", actionName, actionInput);
                    observation = truncate(call(tool, actionInput), properties.maxObservationChars());
                }
            }

            history.add(new Step(parsed.thought(), actionName, actionInput, observation));
            log.info("[观察] {}", observation);
        }

        // 步数用尽：强行让它别再调工具，把已经拿到的信息总结成答案
        String forced = summarizeWithoutTools(question, history);
        log.info("[停止] 已达最大步数 {}，用已有观察直接作答", properties.maxSteps());
        return new AgentRunResult(question, forced, history, false, "max_steps_reached");
    }

    private String call(Tool tool, String input) {
        try {
            String observation = tool.execute(input);
            return observation == null || observation.isBlank() ? "工具执行成功但没有返回内容" : observation;
        } catch (RuntimeException ex) {
            log.warn("工具 {} 执行异常", tool.name(), ex);
            return "工具执行失败: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private String summarizeWithoutTools(String question, List<Step> history) {
        String user = prompt.user(question, history, history.size() + 1)
                + "\n\n补充要求: 不允许再调用工具，直接输出 Final Answer，基于上面已有信息尽量回答，并指出还缺什么。";
        try {
            ParsedStep parsed = parser.parse(llm.complete(prompt.system(), user));
            if (parsed.finalAnswer() != null) {
                return parsed.finalAnswer();
            }
        } catch (RuntimeException ex) {
            log.warn("收尾总结失败: {}", ex.toString());
        }
        return "步数已用完，没能得到最终答案。最后一步的观察是："
                + (history.isEmpty() ? "(无)" : history.get(history.size() - 1).observation());
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...(观察值过长，已截断 " + (text.length() - maxChars) + " 字)";
    }
}

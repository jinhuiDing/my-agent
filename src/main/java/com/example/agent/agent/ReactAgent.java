package com.example.agent.agent;

import java.util.List;

import com.example.agent.agent.ReActParser.ParsedStep;
import com.example.agent.config.AgentProperties;
import com.example.agent.runtime.AgentRunContext;
import com.example.agent.llm.LlmClient;
import com.example.agent.runtime.AgentRunStatus;
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
 * <p>
 * 运行过程通过 AgentListener 实时广播：CLI 逐字打印，HTTP 端点推 SSE；
 * 不传监听器时退回日志输出。
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
        return run(question, new LoggingAgentListener());
    }

    public AgentRunResult run(String question, AgentListener listener) {
        AgentRunContext context = new AgentRunContext(question, properties.maxSteps());
        context.start();
        try {
            listener.onRunStart(question, tools.names(), context.getMaxSteps());

            while (context.getCurrentStep() < context.getMaxSteps()) {
                int step = context.nextStep();
                listener.onStepStart(step, context.getMaxSteps());

                String system = prompt.system();
                String user = prompt.user(context.getQuestion(), context.getHistory(), step);
                String raw = llm.completeStream(system, user, listener::onModelDelta);

                listener.onModelComplete(raw);
                ParsedStep parsed = parser.parse(raw);

                if (parsed.isFinal()) {
                    AgentRunResult result = new AgentRunResult(question, parsed.finalAnswer(), context.getHistory(), true, "final_answer");
                    context.complete();
                    listener.onDone(result);
                    return result;
                }

                String observation;
                String actionName = null;
                String actionInput = parsed.actionInput();
                if (!parsed.hasAction()) {
                    observation = "格式错误: 你的回复里既没有 Action/Action Input，也没有 Final Answer。"
                            + "请重新严格按格式 A 或格式 B 输出，只输出一步。";
                    listener.onNotice(observation);
                } else {
                    Tool tool = tools.find(parsed.action()).orElse(null);
                    if (tool == null) {
                        observation = parser.unknownToolObservation(parsed.action());
                        listener.onNotice(observation);
                    } else {
                        actionName = tool.name();
                        listener.onAction(actionName, actionInput == null ? "" : actionInput);
                        observation = truncate(call(tool, actionInput), properties.maxObservationChars());
                    }
                }

                context.addStep(new Step(parsed.thought(), actionName, actionInput, observation));
                listener.onObservation(observation);
            }
            // 步数用尽：强行让它别再调工具，把已经拿到的信息总结成答案
            listener.onNotice("已达最大步数 " + properties.maxSteps() + "，不再调用工具，用已有信息收尾");
            String forced = summarizeWithoutTools(question, context.getHistory(), listener);

            context.finishByLimit();


            AgentRunResult result = new AgentRunResult(question, forced, context.getHistory(), false, "max_steps_reached");
            listener.onDone(result);
            return result;
        } catch (RuntimeException ex) {
            if (context.getStatus()==AgentRunStatus.RUNNING) {
                context.fail();
            }
            // todo 如果 listener.onError(ex) 自己也抛异常，那么原始异常 ex 可能被覆盖。
            listener.onError(ex);
            throw ex;
        }
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

    private String summarizeWithoutTools(String question, List<Step> history, AgentListener listener) {
        String user = prompt.user(question, history, history.size() + 1)
                + "\n\n补充要求: 不允许再调用工具，直接输出 Final Answer，基于上面已有信息尽量回答，并指出还缺什么。";
        ParsedStep parsed;
        try {
            parsed = parser.parse(llm.completeStream(prompt.system(), user, listener::onModelDelta));
        } catch (RuntimeException ex) {
            listener.onNotice("收尾总结失败: " + ex.getMessage());
            return "步数已用完，没能得到最终答案。最后一步的观察是："
                    + (history.isEmpty() ? "(无)" : history.get(history.size() - 1).observation());
        }
        listener.onModelComplete(parsed.raw());
        if (parsed.finalAnswer() != null) {
            return parsed.finalAnswer();
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

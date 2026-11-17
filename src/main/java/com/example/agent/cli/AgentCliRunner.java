package com.example.agent.cli;

import java.io.PrintStream;
import java.util.Scanner;

import com.example.agent.agent.AgentRunResult;
import com.example.agent.agent.ReactAgent;
import com.example.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 命令行入口：java -jar 后面直接跟问题，或不带参数进入交互模式。
 */
@Component
@ConditionalOnProperty(name = "agent.cli-enabled", havingValue = "true", matchIfMissing = true)
class AgentCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentCliRunner.class);

    private final ReactAgent agent;
    private final AgentProperties properties;

    AgentCliRunner(ReactAgent agent, AgentProperties properties) {
        this.agent = agent;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        PrintStream out = System.out;
        String[] nonOption = args.getNonOptionArgs().toArray(String[]::new);
        String joined = String.join(" ", nonOption).trim();
        if (!joined.isEmpty()) {
            askAndPrint(joined, out);
            return;
        }
        out.printf("""
                ReAct Agent 已启动（模型: %s, 最多 %d 步）
                输入问题回车即可，直接回车跳过，输入 exit 退出。
                """, properties.model(), properties.maxSteps());
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                out.print("\n问题 > ");
                out.flush();
                if (!scanner.hasNextLine()) {
                    return;
                }
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) {
                    return;
                }
                askAndPrint(line, out);
            }
        } catch (IllegalStateException ex) {
            log.debug("标准输入不可用，退出交互模式: {}", ex.getMessage());
        }
    }

    private void askAndPrint(String question, PrintStream out) {
        long started = System.currentTimeMillis();
        try {
            AgentRunResult result = agent.run(question);
            out.printf("""

                    ================= 结论 =================
                    状态: %s (%s)
                    用时: %d ms, 工具调用 %d 次
                    %s
                    ======================================
                    """,
                    result.solved() ? "已解决" : "未完全解决",
                    result.stopReason(),
                    System.currentTimeMillis() - started,
                    result.steps().size(),
                    result.answer() == null ? "(没有最终答案)" : result.answer());
        } catch (RuntimeException ex) {
            out.println("运行失败: " + ex.getMessage());
            log.warn("Agent 运行异常", ex);
        }
    }
}

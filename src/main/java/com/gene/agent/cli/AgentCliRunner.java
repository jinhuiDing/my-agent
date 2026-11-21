package com.gene.agent.cli;

import java.io.PrintStream;
import java.util.Scanner;

import com.gene.agent.agent.AgentListener;
import com.gene.agent.agent.AgentRunResult;
import com.gene.agent.agent.ReactAgent;
import com.gene.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 命令行入口：java -jar 后面直接跟问题，或不带参数进入交互模式。
 * 运行过程实时打印：模型输出逐字流出，工具名、参数、结果即时可见。
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
            AgentRunResult result = agent.run(question, new ConsoleListener(out));
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

    /** 逐字打印模型输出，并在工具执行前后打印结构化信息。 */
    private static final class ConsoleListener implements AgentListener {

        private final PrintStream out;
        private boolean midLine;

        ConsoleListener(PrintStream out) {
            this.out = out;
        }

        @Override
        public void onStepStart(int step, int maxSteps) {
            newLineIfNeeded();
            out.printf("%n---- 第 %d/%d 步 ----%n", step, maxSteps);
        }

        @Override
        public void onModelDelta(String delta) {
            out.print(delta);
            out.flush();
            midLine = !delta.endsWith("\n");
        }

        @Override
        public void onModelComplete(String raw) {
            newLineIfNeeded();
        }

        @Override
        public void onAction(String tool, String input) {
            out.printf("[工具] %s%n[参数] %s%n", tool, input);
            out.flush();
        }

        @Override
        public void onObservation(String observation) {
            out.printf("[结果] %s%n", observation);
            out.flush();
        }

        @Override
        public void onNotice(String message) {
            out.printf("[提示] %s%n", message);
            out.flush();
        }

        @Override
        public void onError(RuntimeException error) {
            out.printf("[错误] %s%n", error.getMessage());
            out.flush();
        }

        private void newLineIfNeeded() {
            if (midLine) {
                out.println();
                midLine = false;
            }
        }
    }
}

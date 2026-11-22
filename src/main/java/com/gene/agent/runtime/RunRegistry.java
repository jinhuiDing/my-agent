package com.gene.agent.runtime;

import com.gene.agent.core.AgentRunTerminationReason;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 进程内运行注册表：runId -> {运行上下文, 运行线程, 超时任务}。
 *
 * <p>它是运行级取消/超时的入口：通过 runId 定位到具体运行，
 * 设置终止原因（写入 AgentRunContext）并中断运行线程，
 * 让正在阻塞的模型流式读取 / 工具调用被打断，主循环在下一轮检测到 isTerminal 后退出。
 *
 * <p>仅进程内实现，服务重启即丢失；持久化留待后续阶段。
 */
@Component
public class RunRegistry {

    /** 一次运行的记录：上下文 + 执行它的线程 + 可选的超时调度任务。 */
    public static class RunningRun {
        private final AgentRunContext context;
        private final Thread thread;
        private volatile ScheduledFuture<?> timeoutFuture;

        RunningRun(AgentRunContext context, Thread thread) {
            this.context = context;
            this.thread = thread;
        }

        public AgentRunContext context() {
            return context;
        }

        public Thread thread() {
            return thread;
        }

        public void timeoutFuture(ScheduledFuture<?> future) {
            this.timeoutFuture = future;
        }

        public ScheduledFuture<?> timeoutFuture() {
            return timeoutFuture;
        }
    }

    private final ConcurrentHashMap<String, RunningRun> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-run-timeout");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 注册运行并启动总超时计时；到点后统一通过 abort 中断运行线程。
     */
    public RunningRun register(AgentRunContext context, Duration timeout) {
        RunningRun run = new RunningRun(context, Thread.currentThread());
        runs.put(context.getRunId(), run);
        run.timeoutFuture(timeoutScheduler.schedule(
                () -> abort(context.getRunId(), AgentRunTerminationReason.TIMEOUT),
                timeout.toMillis(),
                TimeUnit.MILLISECONDS
        ));
        return run;
    }

    public void unregister(String runId) {
        RunningRun run = runs.remove(runId);
        if (run != null && run.timeoutFuture() != null) {
            run.timeoutFuture().cancel(false);
        }
    }

    public Optional<RunningRun> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public List<RunningRun> running() {
        return List.copyOf(runs.values());
    }

    /**
     * 中止一次运行：设置终止原因并中断运行线程。
     *
     * @param reason USER_CANCEL 表示用户主动取消；TIMEOUT 表示运行超时；其它按失败处理
     * @return 是否找到该运行（不代表一定还在运行中）
     */
    public boolean abort(String runId, AgentRunTerminationReason reason) {
        RunningRun run = runs.get(runId);
        if (run == null) {
            return false;
        }

        AgentRunContext context = run.context();
        boolean terminatedByThisCall = false;
        if (!context.isTerminal()) {
            try {
                if (reason == AgentRunTerminationReason.USER_CANCEL) {
                    context.cancel();
                } else {
                    context.fail(reason);
                }
                terminatedByThisCall = true;
            } catch (IllegalStateException ignored) {
                // 与运行线程并发收尾：已经终止了，不能再中断可能正在收尾的线程
            }
        }

        ScheduledFuture<?> timeout = run.timeoutFuture();
        if (timeout != null) {
            timeout.cancel(false);
        }

        // 只有本次调用真正改变了运行状态才中断，避免误中断已经正常结束的运行。
        if (terminatedByThisCall) {
            run.thread().interrupt();
        }
        return true;
    }

    @PreDestroy
    public void shutdown() {
        timeoutScheduler.shutdownNow();
    }
}

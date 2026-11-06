package com.agentcore.spring;

import com.agentcore.application.AgentService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;

/**
 * 会话保留期限（{@code agentcore.sessions.retention}，例如 30d）：启动时和之后每小时，
 * 删除超过期限没有更新的会话，正在运行的除外。在后台守护线程里执行，不拖慢启动，也不阻止进程退出。
 */
public final class ConversationRetention implements DisposableBean {

    private static final System.Logger LOG = System.getLogger(ConversationRetention.class.getName());
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(1);

    private final AgentService service;
    private final Duration retention;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "agentcore-retention");
        thread.setDaemon(true);
        return thread;
    });

    public ConversationRetention(AgentService service, Duration retention) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("agentcore.sessions.retention must be a positive duration, got " + retention);
        }
        this.service = service;
        this.retention = retention;
        scheduler.scheduleWithFixedDelay(this::deleteExpired, 0, CLEANUP_INTERVAL.toMinutes(), TimeUnit.MINUTES);
    }

    /** 删除一次；出错只记日志，下一小时再试。 */
    void deleteExpired() {
        try {
            int deleted = service.deleteNotUpdatedSince(Instant.now().minus(retention));
            if (deleted > 0) {
                LOG.log(System.Logger.Level.INFO, "agentcore: deleted " + deleted + " conversation(s) not updated for " + retention);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "agentcore: failed to delete expired conversations", e);
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}

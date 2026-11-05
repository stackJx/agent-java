package com.agentcore.domain.tool;

import com.agentcore.domain.shared.CancellationToken;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工具运行时上下文。
 *
 * @param state    会话级共享状态（文件读取时间戳、todo 列表等），同一会话内的工具共用
 * @param progress 上报进度：会作为 ToolProgress 事件发出
 * @param services 宿主注入的服务（例如子 Agent 工厂、提问回调），按类型查找
 * @param depth    子 Agent 嵌套深度
 */
public record ToolContext(Path cwd, String conversationId, String callId, Map<String, Object> state,
                          Consumer<String> progress, CancellationToken cancel, Map<Class<?>, Object> services, int depth) {

    public Path resolve(String path) {
        String p = path.startsWith("~/") ? System.getProperty("user.home") + path.substring(1) : path;
        Path candidate = Path.of(p);
        return (candidate.isAbsolute() ? candidate : cwd.resolve(candidate)).normalize().toAbsolutePath();
    }

    public void report(String message) {
        if (progress != null) {
            progress.accept(message);
        }
    }

    public <T> T service(Class<T> type) {
        Object s = services == null ? null : services.get(type);
        return s == null ? null : type.cast(s);
    }
}

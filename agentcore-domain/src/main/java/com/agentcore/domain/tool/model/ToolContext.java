package com.agentcore.domain.tool.model;

import com.agentcore.domain.shared.cancel.CancellationToken;
import com.agentcore.domain.shared.util.FilePaths;
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

    /** 工具参数里的路径 → 绝对路径：支持 ~/ 开头，相对路径按工作目录解析。 */
    public Path resolve(String path) {
        return FilePaths.resolve(cwd, path);
    }

    public void report(String message) {
        if (progress != null) {
            progress.accept(message);
        }
    }

    public <T> T service(Class<T> type) {
        Object service = services == null ? null : services.get(type);
        return service == null ? null : type.cast(service);
    }
}

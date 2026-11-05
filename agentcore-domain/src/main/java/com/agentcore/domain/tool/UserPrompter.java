package com.agentcore.domain.tool;

import java.util.List;

/** 向用户提问的端口（ask_user 工具通过 ToolContext#service 获取）。由宿主实现：命令行、Web、IM…… */
@FunctionalInterface
public interface UserPrompter {
    String ask(String question, List<String> options);
}

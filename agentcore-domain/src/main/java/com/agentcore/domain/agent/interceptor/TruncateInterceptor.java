package com.agentcore.domain.agent.interceptor;

import com.agentcore.domain.conversation.message.ContentBlock;
import com.agentcore.domain.conversation.message.TextBlock;
import com.agentcore.domain.shared.util.Texts;
import com.agentcore.domain.tool.model.ToolOutput;
import java.util.List;

/** 关卡 8：输出太长就截掉中间部分（上限是工具的 maxOutputChars，默认 30000 字符），防止一个工具结果撑爆上下文。 */
public final class TruncateInterceptor implements ToolInterceptor {

    @Override
    public ToolOutput intercept(ToolInvocation invocation, Chain chain) {
        ToolOutput output = chain.proceed(invocation);
        int limit = invocation.tool().maxOutputChars();
        List<ContentBlock> content = output.content().stream()
                .map(block -> block instanceof TextBlock text ? (ContentBlock) new TextBlock(Texts.truncateMiddle(text.text(), limit)) : block)
                .toList();
        return output.withContent(content);
    }
}

package com.agentcore.domain.agent.model;

import com.agentcore.domain.llm.gateway.ModelGateway;
import java.util.Objects;

/** 当前运行冻结的模型，供子 Agent 工具继承；仅借用，不负责关闭网关。 */
public record RunModel(ModelGateway gateway) {
    public RunModel {
        Objects.requireNonNull(gateway, "gateway");
    }
}

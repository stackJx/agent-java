package com.agentcore.interfaces.web;

import com.agentcore.domain.llm.gateway.ModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 页面上可选的模型（端口）。默认实现是 starter 里的 {@code FileModelCatalog}：配置保存在服务端的文件里。
 *
 * <p>实现要保证：读到的总是一个完整的快照；任何返回值和错误信息都不包含 API key。
 */
public interface ModelCatalog {

    /** 可选的模型列表（不含地址和 key）。canManage：调用者能不能管理模型配置。 */
    ObjectNode models(boolean canManage);

    /** 管理员看到的完整配置（API key 只显示有没有）。 */
    ObjectNode settings();

    /** 新建或修改一个配置。 */
    void put(String id, JsonNode profile);

    void delete(String id);

    /** 设为新对话的默认模型。 */
    void setDefault(String id);

    /** 为一次运行准备模型；id 为 null 时用默认模型。运行结束后关闭返回的 {@link Selection}。 */
    Selection select(String id);

    /**
     * 一次运行用的模型。owned 为 true 时网关是专门为这次运行创建的，关闭时一起关掉；
     * 为 false 时是借用的（例如启动时配置的模型），不关闭。关闭多次只生效一次。
     */
    final class Selection implements AutoCloseable {

        private final String modelId;
        private final ModelGateway gateway;
        private final boolean owned;
        private final AtomicBoolean closed = new AtomicBoolean();

        public Selection(String modelId, ModelGateway gateway, boolean owned) {
            this.modelId = modelId;
            this.gateway = gateway;
            this.owned = owned;
        }

        public String modelId() {
            return modelId;
        }

        public ModelGateway gateway() {
            return gateway;
        }

        @Override
        public void close() {
            if (!owned || !closed.compareAndSet(false, true)) {
                return;
            }
            try {
                gateway.close();
            } catch (RuntimeException ignored) {
                // 关闭失败不影响运行结果，也不能让异常信息（可能含 key）传出去
            }
        }
    }

    /** 模型配置操作失败。status 是返回给页面的 HTTP 状态码；message 是固定的文字，不含 key、地址或原始异常。 */
    final class Failure extends RuntimeException {

        private final int status;

        public Failure(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}

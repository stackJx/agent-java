package com.example.agent;

import com.agentcore.domain.tool.ToolException;
import com.agentcore.infrastructure.tool.annotation.AgentTool;
import com.agentcore.infrastructure.tool.annotation.Param;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 业务工具：普通的 Spring Bean，方法上加 @AgentTool 即可被 Agent 调用。
 * 真实项目里这里注入 OrderService / Feign 客户端等。
 */
@Component
public class OrderTools {

    public record Order(String orderId, String status, double amount, String trackingNo) {
    }

    public enum RefundReason { DAMAGED, LATE, WRONG_ITEM, OTHER }

    private final Map<String, Order> orders = Map.of(
            "A1001", new Order("A1001", "已发货", 199.0, "SF1234567890"),
            "A1002", new Order("A1002", "待发货", 58.5, null));

    @AgentTool(description = "查询订单状态、金额和物流单号", readOnly = true)
    public Order getOrder(@Param("订单号，例如 A1001") String orderId) {
        Order o = orders.get(orderId);
        if (o == null) {
            throw new ToolException("订单 " + orderId + " 不存在，请让用户核对订单号");
        }
        return o;
    }

    @AgentTool(description = "给订单退款（不可逆操作，需要用户确认）")
    public String refund(@Param("订单号") String orderId, @Param("退款原因") RefundReason reason, @Param("退款金额（元）") double amount) {
        getOrder(orderId);
        return "已为订单 " + orderId + " 发起退款 " + amount + " 元（原因：" + reason + "），预计 1-3 个工作日到账。";
    }
}

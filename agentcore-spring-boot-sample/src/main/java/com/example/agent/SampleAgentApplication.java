package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例：电商客服 Agent。
 *
 * <pre>
 * export DEEPSEEK_API_KEY=sk-...
 * mvn -pl agentcore-spring-boot-sample spring-boot:run
 *
 * ID=$(curl -s -X POST localhost:8080/api/agent/conversations | jq -r .id)
 * curl -N -X POST localhost:8080/api/agent/conversations/$ID/messages \
 *      -H 'Content-Type: application/json' -d '{"text":"订单 A1001 到哪了？"}'
 * </pre>
 */
@SpringBootApplication
public class SampleAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleAgentApplication.class, args);
    }
}

package com.agentcore.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.domain.llm.gateway.ModelGateway;
import com.agentcore.infrastructure.llm.mock.ScriptedModelGateway;
import com.agentcore.interfaces.web.LocalOnlyInterceptor;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;

/** 没有配置 token：只接受本机请求，跨站请求（Origin）和 DNS rebinding（Host）都被拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentWebLocalOnlyTest.App.class,
        properties = {"agentcore.sessions.enabled=false", "agentcore.memory.enabled=false", "agentcore.skills.enabled=false"})
class AgentWebLocalOnlyTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ModelGateway scriptedModel() {
            return new ScriptedModelGateway().otherwise(r -> ScriptedModelGateway.reply("ok"));
        }
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    int createConversation(String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agent/conversations"))
                .header("Content-Type", "text/plain") // 浏览器"简单请求"用的类型，不会触发 CORS 预检
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** HttpClient 不让改 Host 头，用原始 socket 发请求。 */
    int createConversationWithHost(String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            String request = "POST /api/agent/conversations HTTP/1.1\r\nHost: " + host + "\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 2\r\nConnection: close\r\n\r\n{}";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    @Test
    void localClientsAreAcceptedForeignOriginsAndHostsAreNot() throws Exception {
        assertThat(createConversation(null)).isEqualTo(201);                       // curl 之类的本机命令行
        assertThat(createConversation("http://localhost:3000")).isEqualTo(201);    // 本机的前端页面
        assertThat(createConversation("https://evil.example")).isEqualTo(403);     // 别的网站发来的跨站请求
        assertThat(createConversation("null")).isEqualTo(403);                     // 沙箱页面、本地文件
        assertThat(createConversationWithHost("localhost:" + port)).isEqualTo(201);
        assertThat(createConversationWithHost("evil.example:" + port)).isEqualTo(403); // DNS rebinding
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agent/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void remoteAddressesAndIpv6LoopbackAreHandled() {
        MockHttpServletRequest remote = new MockHttpServletRequest("POST", "/api/agent/conversations");
        remote.setRemoteAddr("10.0.0.5");
        remote.addHeader("Host", "localhost:8080");
        assertThat(LocalOnlyInterceptor.rejectionReason(remote)).contains("only local clients");

        MockHttpServletRequest ipv6 = new MockHttpServletRequest("POST", "/api/agent/conversations");
        ipv6.setRemoteAddr("0:0:0:0:0:0:0:1");
        ipv6.addHeader("Host", "[::1]:8080");
        ipv6.addHeader("Origin", "http://[::1]:8080");
        assertThat(LocalOnlyInterceptor.rejectionReason(ipv6)).isNull();
    }
}

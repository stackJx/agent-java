package com.agentcore.spring.web;

import static org.assertj.core.api.Assertions.*;

import com.agentcore.interfaces.web.BearerTokenInterceptor;
import com.agentcore.interfaces.web.LocalOnlyInterceptor;
import com.agentcore.interfaces.web.WebCaller;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Map;

class ModelAdministrationTest {
    @Test void onlyExplicitSharedTokenConfersAdministration() throws Exception {
        BearerTokenInterceptor auth = new BearerTokenInterceptor("admin", Map.of("alice", "user"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(WebCaller.isAdmin(request)).isFalse();
        request.addHeader("Authorization", "Bearer user");
        assertThat(auth.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        assertThat(WebCaller.userOf(request)).isEqualTo("alice");
        assertThat(WebCaller.isAdmin(request)).isFalse();
        request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer admin");
        assertThat(auth.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        assertThat(request.getAttribute(WebCaller.ADMIN_ATTRIBUTE)).isEqualTo(true);
        assertThat(WebCaller.isAdmin(request)).isTrue();
    }
    @Test void loopbackAddressAloneIsNotAnAdministrationCredential() throws Exception {
        LocalOnlyInterceptor auth = new LocalOnlyInterceptor();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1"); request.addHeader("Host", "localhost:8080");
        assertThat(WebCaller.isAdmin(request)).isFalse();
        assertThat(auth.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        assertThat(WebCaller.isAdmin(request)).isTrue();
        request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1"); request.addHeader("Host", "localhost");
        request.addHeader("Origin", "https://evil.example");
        assertThat(auth.preHandle(request, new MockHttpServletResponse(), null)).isFalse();
        assertThat(WebCaller.isAdmin(request)).isFalse();
    }
    @Test void hostPrincipalsRequireExplicitRoleEvenOnVerifiedLocalRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1"); request.addHeader("Host", "localhost");
        request.setUserPrincipal(() -> "host-user");
        new LocalOnlyInterceptor().preHandle(request, new MockHttpServletResponse(), null);
        assertThat(WebCaller.isAdmin(request)).isFalse();
        request.addUserRole("AGENTCORE_ADMIN");
        assertThat(WebCaller.isAdmin(request)).isTrue();
    }
}

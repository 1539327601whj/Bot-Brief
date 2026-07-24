package com.ai.daily.security;

import com.ai.daily.mapper.UserMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SecurityConfigTest.TestApplication.class)
@AutoConfigureMockMvc
class SecurityConfigTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, SecurityErrorHandlers.class, JwtAuthFilter.class, TestController.class})
    static class TestApplication {
    }

    @RestController
    static class TestController {
        @RequestMapping(path = {
                "/api/auth/me", "/api/reports", "/api/reports/latest", "/api/reports/1",
                "/api/stats/dashboard", "/api/chat", "/api/content-growth/overview",
                "/api/shop/analytics/overview", "/api/subscription", "/api/channels",
                "/api/push-logs", "/api/admin/invite-codes", "/api/market-valuations/000300/latest",
                "/api/health", "/api/push/wechat"
        })
        String ok() {
            return "ok";
        }
    }

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserMapper userMapper;

    @Resource
    private MockMvc mockMvc;

    @Test
    void anonymousCanOnlyReachExistingPublicReadEndpoint() throws Exception {
        mockMvc.perform(get("/api/market-valuations/000300/latest"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void demoCanReadReportsAndDashboard() throws Exception {
        var demo = user("demo@example.com").authorities(
                () -> "ROLE_USER", () -> "ACCOUNT_DEMO");

        mockMvc.perform(get("/api/reports").with(demo)).andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/1").with(demo)).andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/latest").with(demo)).andExpect(status().isOk());
        mockMvc.perform(get("/api/stats/dashboard").with(demo)).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").with(demo)).andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/internal/export").with(demo)).andExpect(status().isForbidden());
    }

    @Test
    void demoIsForbiddenFromNonDemoAreas() throws Exception {
        var demo = user("demo@example.com").authorities(
                () -> "ROLE_USER", () -> "ACCOUNT_DEMO");

        for (String path : new String[]{
                "/api/content-growth/overview", "/api/shop/analytics/overview", "/api/subscription",
                "/api/channels", "/api/push-logs", "/api/admin/invite-codes"
        }) {
            mockMvc.perform(get(path).with(demo))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(403));
        }
        mockMvc.perform(post("/api/chat").with(demo)).andExpect(status().isForbidden());
    }

    @Test
    void disabledWriteEndpointsAreForbiddenForNormalAccounts() throws Exception {
        var normal = user("user@example.com").authorities(
                () -> "ROLE_USER", () -> "ACCOUNT_NORMAL");

        mockMvc.perform(post("/api/reports").with(normal)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/push/wechat").with(normal)).andExpect(status().isForbidden());
    }

    @Test
    void adminRequiresRoleAdmin() throws Exception {
        var normal = user("user@example.com").authorities(
                () -> "ROLE_USER", () -> "ACCOUNT_NORMAL");
        var admin = user("admin@example.com").authorities(
                () -> "ROLE_ADMIN", () -> "ACCOUNT_NORMAL");
        var demoAdmin = user("demo@example.com").authorities(
                () -> "ROLE_ADMIN", () -> "ACCOUNT_DEMO");

        mockMvc.perform(get("/api/admin/invite-codes").with(normal)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/invite-codes").with(demoAdmin)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/invite-codes").with(admin)).andExpect(status().isOk());
    }
}

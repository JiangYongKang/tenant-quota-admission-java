package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreakerManager;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP：准入拒绝的状态码/判定码映射、成功响应可解释字段、日志由控制台人工可见。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeDownstream.Config.class)
class WebEndToEndTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private QuotaManager quotaManager;
    @Autowired
    private CircuitBreakerManager breakerManager;
    @Autowired
    private FakeDownstream downstream;
    @Autowired
    private TenantRegistry registry;

    @BeforeEach
    void reset() {
        downstream.reset();
        quotaManager.reset("tenant-a");
        breakerManager.reset("tenant-a");
        registry.switchState("tenant-a", ForcedState.AUTO);
    }

    @Test
    void missingIdentityReturns401WithCode() throws Exception {
        mockMvc.perform(post("/process"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.admitted").value(false))
                .andExpect(jsonPath("$.decision").value("TENANT_MISSING"));
    }

    @Test
    void wrongTokenReturns401WithDistinctCode() throws Exception {
        mockMvc.perform(post("/process")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Tenant-Token", "bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.decision").value("TENANT_CREDENTIAL_MISMATCH"));
    }

    @Test
    void successReturns200WithExplanation() throws Exception {
        mockMvc.perform(post("/process")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Tenant-Token", "token-a")
                        .header("X-Request-Id", "web-1")
                        .content("ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admitted").value(true))
                .andExpect(jsonPath("$.decision").value("DOWNSTREAM_SUCCESS"))
                .andExpect(jsonPath("$.explanation.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.explanation.quotaAction").value("CONSUME"))
                .andExpect(jsonPath("$.explanation.body").value("ok"));
    }

    @Test
    void forcedDegradedReturns503() throws Exception {
        mockMvc.perform(post("/admin/tenants/tenant-a/state")
                        .contentType("application/json")
                        .content("{\"state\":\"DEGRADED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/process")
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Tenant-Token", "token-a")
                        .header("X-Request-Id", "web-2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.decision").value("DEGRADED"));
    }
}

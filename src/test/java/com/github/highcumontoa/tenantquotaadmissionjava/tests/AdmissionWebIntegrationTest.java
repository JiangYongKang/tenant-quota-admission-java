package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 端到端：HTTP 头 -> 身份/配额/下游 -> 可区分的原因码与 HTTP 状态。 */
@SpringBootTest
@AutoConfigureMockMvc
class AdmissionWebIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AdmissionWebIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingIdentityReturns400WithReason() throws Exception {
        log.info("HTTP 用例: 缺少身份头");
        mockMvc.perform(post("/admission/request").param("requestId", "http-missing")
                        .contentType(MediaType.TEXT_PLAIN).content("p"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("IDENTITY_MISSING"))
                .andExpect(jsonPath("$.rejected").value(true));
    }

    @Test
    void unknownTenantReturns403WithReason() throws Exception {
        mockMvc.perform(post("/admission/request").param("requestId", "http-unknown")
                        .header("X-Tenant-Id", "t-ghost"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("IDENTITY_UNKNOWN"));
    }

    @Test
    void disabledTenantReturns403WithDistinctReason() throws Exception {
        mockMvc.perform(post("/admission/request").param("requestId", "http-disabled")
                        .header("X-Tenant-Id", "t-disabled"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TENANT_DISABLED"));
    }

    @Test
    void happyPathAdmitsAndQuotaIsObservable() throws Exception {
        mockMvc.perform(post("/admission/request").param("requestId", "http-ok-1")
                        .header("X-Tenant-Id", "t-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admitted").value(true))
                .andExpect(jsonPath("$.tenantId").value("t-active"))
                .andExpect(jsonPath("$.rejectReason").doesNotExist());

        mockMvc.perform(get("/admin/quota/t-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        log.info("HTTP 用例: 正常放行 + 配额查询通过");
    }

    @Test
    void adminCanSwitchPolicyAndDegrade() throws Exception {
        mockMvc.perform(post("/admin/policy/t-other")
                        .param("state", "DEGRADED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.state").value("DEGRADED"));

        mockMvc.perform(post("/admission/request").param("requestId", "http-deg")
                        .header("X-Tenant-Id", "t-other"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.rejectReason").value("DEGRADED_BY_POLICY"));

        mockMvc.perform(post("/admin/policy/t-other").param("state", "NORMAL"))
                .andExpect(status().isOk());
        log.info("HTTP 用例: 管理端降级与恢复通过");
    }

    @Test
    void tenantCanBeDisabledAndReEnabledAtRuntime() throws Exception {
        mockMvc.perform(post("/admin/tenant/t-active/status")
                        .param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(post("/admission/request").param("requestId", "http-disabled-runtime")
                        .header("X-Tenant-Id", "t-active"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("TENANT_DISABLED"));

        mockMvc.perform(post("/admin/tenant/t-active/status")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admission/request").param("requestId", "http-reenabled")
                        .header("X-Tenant-Id", "t-active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admitted").value(true));
        log.info("HTTP 用例: 运行时停用/启用通过");
    }
}

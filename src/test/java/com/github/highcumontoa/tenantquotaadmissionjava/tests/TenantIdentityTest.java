package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionResult;
import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.DegradationController;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.RejectReason;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentity;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 非法租户身份必须被拒绝，且拒绝原因彼此可区分。 */
class TenantIdentityTest {

    private static final Logger log = LoggerFactory.getLogger(TenantIdentityTest.class);

    private TenantRegistry registry;
    private QuotaManager quotaManager;
    private DegradationController degradationController;
    private AdmissionService service;

    @BeforeEach
    void setUp() {
        registry = new TenantRegistry();
        registry.register(new com.github.highcumontoa.tenantquotaadmissionjava.tenant.Tenant(
                "t-active",
                com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantStatus.ACTIVE));
        registry.register(new com.github.highcumontoa.tenantquotaadmissionjava.tenant.Tenant(
                "t-disabled",
                com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantStatus.DISABLED));
        quotaManager = new QuotaManager();
        quotaManager.initTenant("t-active", 10);
        quotaManager.initTenant("t-disabled", 10);
        degradationController = new DegradationController(quotaManager);
        DownstreamClient client = mock(DownstreamClient.class);
        when(client.invoke(any(), anyString(), any())).thenReturn("ok");
        service = new AdmissionService(registry,
                new com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentityResolver(),
                quotaManager, degradationController, client);
    }

    private void expectReject(String caseName, Map<String, String> headers,
                              RejectReason expected) {
        log.info(">>> 用例[{}] 输入 headers={}, 期望原因={}", caseName, headers, expected);
        AdmissionException ex = assertThrows(AdmissionException.class,
                () -> service.handle("req-" + caseName, headers, "payload"));
        assertEquals(expected, ex.getReason(), "用例 " + caseName + " 原因不匹配");
        log.info("<<< 用例[{}] 实际拒绝原因={} message={}", caseName, ex.getReason(),
                ex.getMessage());
    }

    @Test
    void missingIdentityIsRejected() {
        expectReject("missing", new HashMap<>(), RejectReason.IDENTITY_MISSING);
    }

    @Test
    void unknownTenantIsRejected() {
        expectReject("unknown", Map.of("x-tenant-id", "t-ghost"),
                RejectReason.IDENTITY_UNKNOWN);
    }

    @Test
    void disabledTenantIsRejected() {
        expectReject("disabled", Map.of("x-tenant-id", "t-disabled"),
                RejectReason.TENANT_DISABLED);
    }

    @Test
    void conflictingSourcesAreRejected() {
        expectReject("conflict",
                Map.of("x-tenant-id", "t-active", "x-tenant-token", "t-other"),
                RejectReason.SOURCE_CONFLICT);
    }

    @Test
    void consistentSourcesPass() {
        Map<String, String> headers = Map.of("x-tenant-id", "t-active",
                "x-tenant-token", "t-active");
        AdmissionResult result = service.handle("req-ok", headers, "payload");
        log.info("一致来源用例结果: admitted={}, reason={}, used={}",
                result.admitted(), result.rejectReason(),
                result.quotaSnapshot().used());
        assertEquals(true, result.admitted());
        assertEquals(1L, result.quotaSnapshot().used());
    }
}

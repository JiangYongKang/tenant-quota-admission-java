package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreakerManager;
import com.github.highcumontoa.tenantquotaadmissionjava.common.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ProcessResult;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantConfig;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行时策略切换：正常 -> 限流 -> 降级 -> 恢复，对新准入请求立即一致生效，版本号递增。
 */
@SpringBootTest
@Import(FakeDownstream.Config.class)
class StateSwitchTest {

    private static final String T = "tenant-switch";

    @Autowired
    private AdmissionService admissionService;
    @Autowired
    private TenantRegistry registry;
    @Autowired
    private QuotaManager quotaManager;
    @Autowired
    private CircuitBreakerManager breakerManager;
    @Autowired
    private FakeDownstream downstream;

    @BeforeEach
    void setUp() {
        downstream.reset();
        quotaManager.reset(T);
        breakerManager.reset(T);
        registry.register(new TenantConfig(T, true, "tok", 5, 60_000,
                ForcedState.AUTO, 1));
    }

    @Test
    void switchesTakeEffectImmediatelyAndRecovers() {
        ProcessResult ok = admissionService.process(T, null, "tok", "s-1", null);
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, ok.decision());

        TenantConfig limited = registry.switchState(T, ForcedState.RATE_LIMITED);
        assertEquals(2, limited.version());
        AdmissionException rejected = assertThrows(AdmissionException.class,
                () -> admissionService.process(T, null, "tok", "s-2", null));
        assertEquals(DecisionCode.QUOTA_EXHAUSTED, rejected.getCode());
        assertTrue(rejected.getMessage().contains("FORCED_RATE_LIMITED"));

        TenantConfig degraded = registry.switchState(T, ForcedState.DEGRADED);
        assertEquals(3, degraded.version());
        AdmissionException degradedEx = assertThrows(AdmissionException.class,
                () -> admissionService.process(T, null, "tok", "s-3", null));
        assertEquals(DecisionCode.DEGRADED, degradedEx.getCode());
        assertTrue(degradedEx.getMessage().contains("FORCED_DEGRADED"));

        TenantConfig back = registry.switchState(T, ForcedState.AUTO);
        assertEquals(4, back.version());
        ProcessResult recovered = admissionService.process(T, null, "tok", "s-4", null);
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, recovered.decision());
        assertEquals(4L, recovered.explanation().get("policyVersion"));
    }

    @Test
    void forcedRateLimitedDoesNotConsumeQuotaOrCallDownstream() {
        registry.switchState(T, ForcedState.RATE_LIMITED);
        assertThrows(AdmissionException.class,
                () -> admissionService.process(T, null, "tok", "s-f1", null));
        assertEquals(0, downstream.getInvocations(), "强制限流不得调用下游");
        com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaView view =
                quotaManager.snapshot(T);
        assertTrue(view == null || view.used() + view.inFlight() == 0,
                "强制限流不得消耗配额");
    }
}

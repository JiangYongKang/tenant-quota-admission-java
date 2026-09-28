package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.common.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ProcessResult;
import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreakerManager;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantConfig;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下游四类结果的可区分判定、配额动作与熔断行为：
 * 业务拒绝/内部失败/不可用 -> 返还配额；超时 -> 保留配额；
 * 连续失败触发降级，冷却后探测恢复，期间不因短暂波动反复抖动。
 */
@SpringBootTest
@Import(FakeDownstream.Config.class)
@TestPropertySource(properties = {
        "tenant.breaker.failure-threshold=3",
        "tenant.breaker.cooldown-millis=400"
})
class DownstreamExceptionTest {

    private static final String T = "tenant-exc";

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
        registry.register(new TenantConfig(T, true, "tok", 100, 60_000,
                ForcedState.AUTO, 1));
    }

    @Test
    void bizRejectionIsClassifiedAndQuotaRefunded() {
        downstream.setMode(FakeDownstream.Mode.BIZ_REJECT);
        ProcessResult r = admissionService.process(T, null, "tok", "e-biz", null);
        assertEquals(DecisionCode.DOWNSTREAM_BIZ_REJECTED, r.decision());
        assertEquals("REFUND", r.explanation().get("quotaAction"));
        assertEquals(0L, quotaManager.snapshot(T).used());
        assertEquals(0L, quotaManager.snapshot(T).inFlight());
    }

    @Test
    void timeoutIsClassifiedAndQuotaRetained() {
        downstream.setMode(FakeDownstream.Mode.TIMEOUT);
        ProcessResult r = admissionService.process(T, null, "tok", "e-timeout", null);
        assertEquals(DecisionCode.DOWNSTREAM_TIMEOUT, r.decision());
        assertEquals("RETAIN", r.explanation().get("quotaAction"));
        assertEquals(1L, quotaManager.snapshot(T).used(), "超时保留配额");
        assertEquals(0L, quotaManager.snapshot(T).inFlight());
    }

    @Test
    void internalErrorIsClassifiedAndQuotaRefunded() {
        downstream.setMode(FakeDownstream.Mode.INTERNAL_ERROR);
        ProcessResult r = admissionService.process(T, null, "tok", "e-500", null);
        assertEquals(DecisionCode.DOWNSTREAM_INTERNAL_ERROR, r.decision());
        assertEquals("REFUND", r.explanation().get("quotaAction"));
        assertEquals(0L, quotaManager.snapshot(T).used());
    }

    @Test
    void unavailableIsClassifiedAndQuotaRefunded() {
        downstream.setMode(FakeDownstream.Mode.UNAVAILABLE);
        ProcessResult r = admissionService.process(T, null, "tok", "e-503", null);
        assertEquals(DecisionCode.DOWNSTREAM_UNAVAILABLE, r.decision());
        assertEquals("REFUND", r.explanation().get("quotaAction"));
        assertEquals(0L, quotaManager.snapshot(T).used());
    }

    @Test
    void breakerTripsAfterConsecutiveFailuresThenRecoversViaProbe() throws Exception {
        downstream.setMode(FakeDownstream.Mode.UNAVAILABLE);

        admissionService.process(T, null, "tok", "f-1", null);
        admissionService.process(T, null, "tok", "f-2", null);
        admissionService.process(T, null, "tok", "f-3", null);
        int callsAfterThree = downstream.getInvocations();
        assertEquals(3, callsAfterThree);

        // 第 4 次：熔断打开，直接降级，不调用下游
        AdmissionException tripped = assertThrows(AdmissionException.class,
                () -> admissionService.process(T, null, "tok", "f-4", null));
        assertEquals(DecisionCode.DEGRADED, tripped.getCode());
        assertTrue(tripped.getMessage().contains("BREAKER_OPEN"));
        assertEquals(3, downstream.getInvocations(), "熔断打开后不得再调用下游");

        // 冷却期内持续拒绝（防抖）
        Thread.sleep(150);
        assertThrows(AdmissionException.class,
                () -> admissionService.process(T, null, "tok", "f-5", null));
        assertEquals(3, downstream.getInvocations());

        // 冷却结束后仅放行一个探测请求；探测成功则恢复
        Thread.sleep(350);
        downstream.setMode(FakeDownstream.Mode.SUCCESS);
        ProcessResult probe = admissionService.process(T, null, "tok", "f-6", null);
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, probe.decision());
        assertEquals(4, downstream.getInvocations());

        ProcessResult again = admissionService.process(T, null, "tok", "f-7", null);
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, again.decision());
        assertTrue(((String) again.explanation().get("breakerAfter")).contains("CLOSED"));
    }

    @Test
    void singleBlipDoesNotTripBreaker() {
        downstream.setMode(FakeDownstream.Mode.INTERNAL_ERROR);
        admissionService.process(T, null, "tok", "b-1", null);
        downstream.setMode(FakeDownstream.Mode.SUCCESS);
        ProcessResult r = admissionService.process(T, null, "tok", "b-2", null);
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, r.decision());
        assertTrue(((String) r.explanation().get("breakerAfter")).contains("CLOSED"),
                "单次波动后成功应清零失败计数，不得熔断");
    }

    @Test
    void everyDecisionIsExplainable() {
        downstream.setMode(FakeDownstream.Mode.TIMEOUT);
        ProcessResult r = admissionService.process(T, null, "tok", "x-1", null);
        Map<String, Object> ex = r.explanation();
        assertTrue(ex.containsKey("tenantId"));
        assertTrue(ex.containsKey("requestId"));
        assertTrue(ex.containsKey("quotaBefore"));
        assertTrue(ex.containsKey("quotaAfter"));
        assertTrue(ex.containsKey("quotaAction"));
        assertTrue(ex.containsKey("breaker"));
        assertTrue(ex.containsKey("downstream"));
    }
}

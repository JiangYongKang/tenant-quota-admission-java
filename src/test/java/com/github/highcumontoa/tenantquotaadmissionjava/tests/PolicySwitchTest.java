package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionResult;
import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.SimulatedDownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.DegradationController;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaPolicy;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaSnapshot;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaState;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.RejectReason;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.Tenant;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentityResolver;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantStatus;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 运行时切换配额策略，切换对后续请求立即生效，拒绝原因与状态可解释。 */
class PolicySwitchTest {

    private static final Logger log = LoggerFactory.getLogger(PolicySwitchTest.class);

    private final TenantRegistry registry = new TenantRegistry();
    private final QuotaManager quotaManager = new QuotaManager();
    private final SimulatedDownstreamClient client = new SimulatedDownstreamClient();
    private final DegradationController controller = new DegradationController(quotaManager);
    private final AdmissionService service;

    PolicySwitchTest() {
        registry.register(new Tenant("t-active", TenantStatus.ACTIVE));
        quotaManager.initTenant("t-active", 100);
        service = new AdmissionService(registry, new TenantIdentityResolver(),
                quotaManager, controller, client);
    }

    private AdmissionResult call(String requestId) {
        return service.handle(requestId, Map.of("x-tenant-id", "t-active"), "p");
    }

    @Test
    void stateTransitionsTakeEffectForSubsequentRequests() {
        Map<RejectReason, Integer> reasons = new HashMap<>();

        // NORMAL：全部通过
        for (int i = 0; i < 50; i++) {
            AdmissionResult r = call("n-" + i);
            assertTrue(r.admitted(), "NORMAL 下应放行: n-" + i);
        }
        log.info("NORMAL 阶段后: {}", quotaManager.snapshot("t-active"));

        // THROTTLED 100%：全部限流
        controller.manualOverride("t-active", QuotaState.THROTTLED, 100);
        for (int i = 0; i < 20; i++) {
            AdmissionResult r = call("t-" + i);
            assertFalse(r.admitted());
            assertEquals(QuotaState.THROTTLED, r.quotaSnapshot().state());
            reasons.merge(r.rejectReason(), 1, Integer::sum);
        }
        log.info("THROTTLED 阶段拒绝原因统计: {}", reasons);
        assertEquals(20, reasons.get(RejectReason.THROTTLED_BY_POLICY));
        assertEquals(50L, quotaManager.snapshot("t-active").used(), "限流请求不得占用配额");

        // DEGRADED：全部快速失败
        controller.manualOverride("t-active", QuotaState.DEGRADED, 0);
        AdmissionResult degraded = call("d-1");
        assertEquals(RejectReason.DEGRADED_BY_POLICY, degraded.rejectReason());
        assertEquals(QuotaState.DEGRADED, degraded.quotaSnapshot().state());
        assertFalse(degraded.quotaRefunded(), "闸门拒绝从未占用，不应标记返还");
        log.info("DEGRADED 判定: reason={}, explanation={}",
                degraded.rejectReason(), degraded.decisionExplanation());

        // 恢复 NORMAL：重新放行
        controller.manualOverride("t-active", QuotaState.NORMAL, 0);
        AdmissionResult recovered = call("r-1");
        assertTrue(recovered.admitted(), "恢复后应重新放行");

        QuotaSnapshot snapshot = quotaManager.snapshot("t-active");
        log.info("恢复后最终: used={}, inflight={}, state={}",
                snapshot.used(), snapshot.inflight(), snapshot.state());
        assertEquals(51L, snapshot.used(), "只有真正放行的请求计入用量");
        assertEquals(0L, snapshot.inflight(), "配额守恒：无残留占用");
        assertTrue(snapshot.used() + snapshot.inflight() <= snapshot.capacity());
    }

    @Test
    void throttlingIsPartialAndStable() {
        controller.manualOverride("t-active", QuotaState.THROTTLED, 50);
        int admitted = 0;
        int throttled = 0;
        for (int i = 0; i < 100; i++) {
            AdmissionResult r = call("pct-" + i);
            if (r.admitted()) {
                admitted++;
            } else {
                assertEquals(RejectReason.THROTTLED_BY_POLICY, r.rejectReason());
                throttled++;
            }
        }
        // 判定基于 requestId 哈希：相同 requestId 结果必须稳定一致
        AdmissionResult again = call("pct-0");
        AdmissionResult first = service.handle("pct-0", Map.of("x-tenant-id", "t-active"), "p");
        log.info("50% 限流: admitted={}, throttled={}, 同一requestId回放 admitted={}/{}",
                admitted, throttled, first.admitted(), again.admitted());
        assertTrue(admitted >= 35 && admitted <= 65, "约一半请求应放行，实际 " + admitted);
        assertEquals(100, admitted + throttled);
        assertEquals(first.admitted(), again.admitted(), "同一 requestId 判定必须一致");
    }
}

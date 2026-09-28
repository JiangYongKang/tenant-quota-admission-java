package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionResult;
import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamOutcome;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.SimulatedDownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.DegradationController;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaSnapshot;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaState;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.RejectReason;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.Tenant;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentityResolver;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 租户隔离：配额账户独立、失败与降级互不影响、结果中只携带本租户快照。 */
class TenantIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(TenantIsolationTest.class);

    private final TenantRegistry registry = new TenantRegistry();
    private final QuotaManager qm = new QuotaManager();
    private final SimulatedDownstreamClient client = new SimulatedDownstreamClient();
    private final DegradationController controller = new DegradationController(qm);
    private final AdmissionService service = new AdmissionService(
            registry, new TenantIdentityResolver(), qm, controller, client);

    TenantIsolationTest() {
        registry.register(new Tenant("t-a", TenantStatus.ACTIVE));
        registry.register(new Tenant("t-b", TenantStatus.ACTIVE));
        qm.initTenant("t-a", 3);
        qm.initTenant("t-b", 100);
    }

    @Test
    void quotaPoolsAreIndependent() {
        // t-a 打满自己的小容量
        for (int i = 0; i < 3; i++) {
            assertTrue(service.handle("a-" + i, Map.of("x-tenant-id", "t-a"), "p")
                    .admitted());
        }
        AdmissionResult aExhausted = service.handle("a-overflow",
                Map.of("x-tenant-id", "t-a"), "p");
        assertEquals(RejectReason.QUOTA_EXHAUSTED, aExhausted.rejectReason());

        // t-b 完全不受影响
        AdmissionResult b = service.handle("b-1",
                Map.of("x-tenant-id", "t-b"), "p");
        assertTrue(b.admitted());
        QuotaSnapshot bs = qm.snapshot("t-b");
        log.info("隔离断言: t-a.used={}/3, t-b.used={}/100 state={}",
                qm.snapshot("t-a").used(), bs.used(), bs.state());
        assertEquals(1L, bs.used());
        assertEquals(QuotaState.NORMAL, bs.state());
    }

    @Test
    void degradationOfOneTenantDoesNotAffectOthers() {
        client.setTenantOutcome("t-a", DownstreamOutcome.UNAVAILABLE);
        int downstreamRejected = 0;
        int degradedFastFailed = 0;
        for (int i = 0; i < 8; i++) {
            AdmissionResult r = service.handle("a-fail-" + i,
                    Map.of("x-tenant-id", "t-a"), "p");
            if (r.rejectReason() == RejectReason.DOWNSTREAM_UNAVAILABLE) {
                downstreamRejected++;
                assertTrue(r.quotaRefunded());
            } else if (r.rejectReason() == RejectReason.DEGRADED_BY_POLICY) {
                degradedFastFailed++;
            }
        }
        // 前若干次失败透传并返还，达到阈值后转为降级快速失败
        assertTrue(downstreamRejected >= 4, "应至少有 4 次下游失败被透传");
        assertTrue(degradedFastFailed > 0, "持续失败后应转为降级快速失败");
        assertEquals(QuotaState.DEGRADED, qm.snapshot("t-a").state());

        // t-a 现在被快速失败
        AdmissionResult aDegraded = service.handle("a-deg",
                Map.of("x-tenant-id", "t-a"), "p");
        assertEquals(RejectReason.DEGRADED_BY_POLICY, aDegraded.rejectReason());

        // t-b 仍然正常放行，且结果快照只属于 t-b
        AdmissionResult b = service.handle("b-ok",
                Map.of("x-tenant-id", "t-b"), "p");
        assertTrue(b.admitted());
        assertEquals("t-b", b.quotaSnapshot().tenantId());
        assertEquals(QuotaState.NORMAL, b.quotaSnapshot().state());
        assertNotEquals(QuotaState.DEGRADED, qm.snapshot("t-b").state());
        log.info("降级隔离: t-a={}, t-b={}, t-a.used={}",
                qm.snapshot("t-a").state(), qm.snapshot("t-b").state(),
                qm.snapshot("t-a").used());
        assertEquals(0L, qm.snapshot("t-a").used(), "t-a 失败全部返还");
    }

    @Test
    void resultNeverExposesAnotherTenantSnapshot() {
        service.handle("a-x", Map.of("x-tenant-id", "t-a"), "p");
        AdmissionResult b = service.handle("b-x",
                Map.of("x-tenant-id", "t-b"), "p");
        assertEquals("t-b", b.tenantId());
        assertEquals("t-b", b.quotaSnapshot().tenantId());
        assertThrows(AdmissionException.class, () -> service.handle("spy",
                Map.of("x-tenant-id", "t-ghost"), "p"));
    }
}

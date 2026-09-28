package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionResult;
import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamOutcome;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.SimulatedDownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.DegradationController;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaSnapshot;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.RejectReason;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.Tenant;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentity;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentityResolver;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 下游四类异常必须可区分，且占用全部返还、配额守恒。 */
class DownstreamExceptionTest {

    private static final Logger log = LoggerFactory.getLogger(DownstreamExceptionTest.class);

    private AdmissionService newService(QuotaManager qm, DownstreamClient client) {
        TenantRegistry registry = new TenantRegistry();
        registry.register(new Tenant("t-active", TenantStatus.ACTIVE));
        return new AdmissionService(registry, new TenantIdentityResolver(),
                qm, new DegradationController(qm), client);
    }

    @Test
    void eachDownstreamOutcomeIsClassifiedAndRefunded() {
        record Case(String req, DownstreamOutcome outcome, RejectReason reason) {
        }
        Case[] cases = {
                new Case("biz", DownstreamOutcome.BUSINESS_REJECTED,
                        RejectReason.DOWNSTREAM_BUSINESS_REJECTED),
                new Case("timeout", DownstreamOutcome.TIMEOUT,
                        RejectReason.DOWNSTREAM_TIMEOUT),
                new Case("internal", DownstreamOutcome.INTERNAL_FAILURE,
                        RejectReason.DOWNSTREAM_INTERNAL_FAILURE),
                new Case("unavail", DownstreamOutcome.UNAVAILABLE,
                        RejectReason.DOWNSTREAM_UNAVAILABLE),
        };
        for (Case c : cases) {
            QuotaManager qm = new QuotaManager();
            qm.initTenant("t-active", 5);
            SimulatedDownstreamClient client = new SimulatedDownstreamClient();
            client.setRequestOutcome(c.req(), c.outcome());
            AdmissionService service = newService(qm, client);

            AdmissionResult r = service.handle(c.req(),
                    Map.of("x-tenant-id", "t-active"), "p");
            QuotaSnapshot s = qm.snapshot("t-active");
            log.info("异常用例[{}]: admitted={}, reason={}, refunded={}, used={}, inflight={}, 说明={}",
                    c.outcome(), r.admitted(), r.rejectReason(), r.quotaRefunded(),
                    s.used(), s.inflight(), r.decisionExplanation());

            assertFalse(r.admitted());
            assertEquals(c.reason(), r.rejectReason(), "原因必须可区分: " + c.outcome());
            assertTrue(r.quotaRefunded(), c.outcome() + " 必须返还配额");
            assertEquals(0L, s.used(), "失败不计入用量");
            assertEquals(0L, s.inflight(), "失败后不得残留占用");
        }
    }

    @Test
    void successConsumesQuotaWithoutRefund() {
        QuotaManager qm = new QuotaManager();
        qm.initTenant("t-active", 5);
        AdmissionService service = newService(qm, new SimulatedDownstreamClient());

        AdmissionResult r = service.handle("ok-1",
                Map.of("x-tenant-id", "t-active"), "p");
        QuotaSnapshot s = qm.snapshot("t-active");
        log.info("成功用例: admitted={}, refunded={}, used={}, inflight={}",
                r.admitted(), r.quotaRefunded(), s.used(), s.inflight());
        assertTrue(r.admitted());
        assertFalse(r.quotaRefunded());
        assertEquals("ok:ok-1", r.downstreamResponse());
        assertEquals(1L, s.used());
        assertEquals(0L, s.inflight());
    }

    @Test
    void nonDownstreamRuntimeExceptionIsAlsoRefunded() {
        QuotaManager qm = new QuotaManager();
        qm.initTenant("t-active", 5);
        DownstreamClient explosive = new DownstreamClient() {
            @Override
            public String invoke(TenantIdentity identity, String requestId, String payload) {
                throw new IllegalStateException("下游抛了未预期的运行时异常");
            }
        };
        AdmissionService service = newService(qm, explosive);
        AdmissionResult r = service.handle("boom",
                Map.of("x-tenant-id", "t-active"), "p");
        QuotaSnapshot s = qm.snapshot("t-active");
        log.info("兜底用例: reason={}, refunded={}, used={}, inflight={}",
                r.rejectReason(), r.quotaRefunded(), s.used(), s.inflight());
        assertEquals(RejectReason.DOWNSTREAM_UNCLASSIFIED, r.rejectReason());
        assertTrue(r.quotaRefunded());
        assertEquals(0L, s.used());
        assertEquals(0L, s.inflight(), "未分类异常也不能泄漏配额");
    }
}

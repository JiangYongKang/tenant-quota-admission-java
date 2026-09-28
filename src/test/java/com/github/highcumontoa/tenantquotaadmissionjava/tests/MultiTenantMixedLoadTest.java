package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionResult;
import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamTimeoutException;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 多租户混合负载：t-a 全部成功、t-b 全部超时，并发交错，各租户独立守恒且互不影响。 */
class MultiTenantMixedLoadTest {

    private static final Logger log = LoggerFactory.getLogger(MultiTenantMixedLoadTest.class);

    static class MixedClient implements DownstreamClient {
        @Override
        public String invoke(TenantIdentity identity, String requestId, String payload) {
            if ("t-b".equals(identity.tenantId())) {
                throw new DownstreamTimeoutException("mixed timeout " + requestId);
            }
            return "ok";
        }
    }

    @Test
    void mixedLoadKeepsEachTenantConsistent() throws Exception {
        TenantRegistry registry = new TenantRegistry();
        registry.register(new Tenant("t-a", TenantStatus.ACTIVE));
        registry.register(new Tenant("t-b", TenantStatus.ACTIVE));
        QuotaManager qm = new QuotaManager();
        qm.initTenant("t-a", 10);
        qm.initTenant("t-b", 10);
        AdmissionService service = new AdmissionService(registry, new TenantIdentityResolver(),
                qm, new DegradationController(qm), new MixedClient());

        int perTenant = 60;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(perTenant * 2);
        ConcurrentMap<String, AtomicInteger> admitted = new ConcurrentHashMap<>();
        ConcurrentMap<String, AtomicInteger> rejected = new ConcurrentHashMap<>();
        admitted.put("t-a", new AtomicInteger());
        admitted.put("t-b", new AtomicInteger());
        rejected.put("t-a", new AtomicInteger());
        rejected.put("t-b", new AtomicInteger());

        for (int i = 0; i < perTenant; i++) {
            String ra = "a-" + i;
            String rb = "b-" + i;
            pool.submit(() -> run(service, start, done, "t-a", ra, admitted, rejected));
            pool.submit(() -> run(service, start, done, "t-b", rb, admitted, rejected));
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        QuotaSnapshot a = qm.snapshot("t-a");
        QuotaSnapshot b = qm.snapshot("t-b");
        log.info("混合负载结果: t-a admitted={} rejected={} used={} inflight={} | "
                        + "t-b admitted={} rejected={} used={} inflight={}",
                admitted.get("t-a").get(), rejected.get("t-a").get(), a.used(), a.inflight(),
                admitted.get("t-b").get(), rejected.get("t-b").get(), b.used(), b.inflight());

        // t-a：恰好装满容量，其余配额耗尽
        assertEquals(10L, a.used());
        assertEquals(0L, a.inflight());
        assertEquals(10, admitted.get("t-a").get());
        assertEquals(perTenant - 10, rejected.get("t-a").get());
        assertEquals(com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaState.NORMAL, a.state(), "t-a 不应被 t-b 的失败拖入降级");

        // t-b：全部失败，无正式用量、无残留占用，所有 60 个请求都必须有可区分拒绝原因
        assertEquals(0L, b.used());
        assertEquals(0L, b.inflight());
        assertEquals(0, admitted.get("t-b").get());
        assertEquals(perTenant, rejected.get("t-b").get());
    }

    private void run(AdmissionService service, CountDownLatch start, CountDownLatch done,
                     String tenant, String requestId,
                     ConcurrentMap<String, AtomicInteger> admitted,
                     ConcurrentMap<String, AtomicInteger> rejected) {
        try {
            start.await();
            AdmissionResult r = service.handle(requestId,
                    Map.of("x-tenant-id", tenant), "p");
            if (r.admitted()) {
                admitted.get(tenant).incrementAndGet();
            } else if ("t-a".equals(tenant)
                    ? r.rejectReason() == RejectReason.QUOTA_EXHAUSTED
                    : r.rejectReason() == RejectReason.DOWNSTREAM_TIMEOUT
                            || r.rejectReason() == RejectReason.DEGRADED_BY_POLICY) {
                rejected.get(tenant).incrementAndGet();
            }
        } catch (Exception e) {
            log.error("混合负载异常: tenant={}, requestId={}", tenant, requestId, e);
        } finally {
            done.countDown();
        }
    }
}

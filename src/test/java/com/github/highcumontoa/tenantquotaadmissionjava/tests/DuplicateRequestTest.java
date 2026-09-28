package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 同一 requestId 重试（顺序或并发）只能扣减一次、调用下游一次。 */
class DuplicateRequestTest {

    private static final Logger log = LoggerFactory.getLogger(DuplicateRequestTest.class);

    static class CountingClient extends SimulatedDownstreamClient {
        final AtomicInteger invocations = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String invoke(TenantIdentity identity, String requestId, String payload) {
            invocations.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.invoke(identity, requestId, payload);
        }
    }

    private AdmissionService newService(QuotaManager quotaManager,
                                        CountingClient client) {
        TenantRegistry registry = new TenantRegistry();
        registry.register(new Tenant("t-active", TenantStatus.ACTIVE));
        return new AdmissionService(registry, new TenantIdentityResolver(),
                quotaManager, new DegradationController(quotaManager), client);
    }

    @Test
    void sequentialRetryDeductsOnce() {
        QuotaManager quotaManager = new QuotaManager();
        quotaManager.initTenant("t-active", 10);
        CountingClient client = new CountingClient();
        client.release.countDown();
        AdmissionService service = newService(quotaManager, client);

        AdmissionResult first = service.handle("dup-1",
                Map.of("x-tenant-id", "t-active"), "p");
        AdmissionResult retry = service.handle("dup-1",
                Map.of("x-tenant-id", "t-active"), "p");

        log.info("顺序重试: first.admitted={}, retry.admitted={}, invocations={}, used={}",
                first.admitted(), retry.admitted(), client.invocations.get(),
                quotaManager.snapshot("t-active").used());
        assertEquals(true, first.admitted());
        assertEquals(true, retry.admitted());
        assertEquals(1, client.invocations.get(), "下游只能被调用一次");
        assertEquals(1L, quotaManager.snapshot("t-active").used(), "重试不能重复扣减");
    }

    @Test
    void concurrentDuplicatesDeductOnce() throws Exception {
        QuotaManager quotaManager = new QuotaManager();
        quotaManager.initTenant("t-active", 10);
        CountingClient client = new CountingClient();
        AdmissionService service = newService(quotaManager, client);

        int duplicates = 10;
        ExecutorService pool = Executors.newFixedThreadPool(duplicates);
        CountDownLatch start = new CountDownLatch(1);
        List<AdmissionResult> results = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(duplicates);
        for (int i = 0; i < duplicates; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    AdmissionResult r = service.handle("dup-concurrent",
                            Map.of("x-tenant-id", "t-active"), "p");
                    synchronized (results) {
                        results.add(r);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue2(client.entered.await(5, TimeUnit.SECONDS));
        client.release.countDown();
        assertTrue2(done.await(15, TimeUnit.SECONDS));
        pool.shutdown();

        long admitted = results.stream().filter(AdmissionResult::admitted).count();
        QuotaSnapshot snapshot = quotaManager.snapshot("t-active");
        log.info("并发重复: duplicates={}, admitted={}, invocations={}, used={}, inflight={}",
                duplicates, admitted, client.invocations.get(),
                snapshot.used(), snapshot.inflight());
        assertEquals(duplicates, admitted, "所有重复请求都应回放成功结果");
        assertEquals(1, client.invocations.get(), "下游只执行一次");
        assertEquals(1L, snapshot.used(), "只扣减一次");
        assertEquals(0L, snapshot.inflight(), "占用全部结算");
    }

    @Test
    void duplicateOfFailedRequestRefundsOnce() {
        QuotaManager quotaManager = new QuotaManager();
        quotaManager.initTenant("t-active", 10);
        CountingClient client = new CountingClient();
        client.setRequestOutcome("dup-fail", DownstreamOutcome.TIMEOUT);
        client.release.countDown();
        AdmissionService service = newService(quotaManager, client);

        AdmissionResult first = service.handle("dup-fail",
                Map.of("x-tenant-id", "t-active"), "p");
        AdmissionResult retry = service.handle("dup-fail",
                Map.of("x-tenant-id", "t-active"), "p");

        QuotaSnapshot snapshot = quotaManager.snapshot("t-active");
        log.info("失败重试: first.admitted={}, first.reason={}, refunded={}/{}, used={}, inflight={}",
                first.admitted(), first.rejectReason(), first.quotaRefunded(),
                retry.quotaRefunded(), snapshot.used(), snapshot.inflight());
        assertEquals(false, first.admitted());
        assertEquals(RejectReason.DOWNSTREAM_TIMEOUT, first.rejectReason());
        assertEquals(true, first.quotaRefunded());
        assertEquals(1, client.invocations.get());
        assertEquals(0L, snapshot.used());
        assertEquals(0L, snapshot.inflight(), "返还后不得残留占用");
    }

    private static void assertTrue2(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}

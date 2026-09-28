package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionResult;
import com.github.highcumontoa.tenantquotaadmissionjava.admission.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.DegradationController;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaSnapshot;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.RejectReason;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentity;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantStatus;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.Tenant;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentityResolver;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 高并发下：扣减不丢失、不超卖、used+inflight 守恒且不超过容量。 */
class ConcurrentQuotaTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentQuotaTest.class);

    private static final long CAPACITY = 20;
    private static final int THREADS = 100;

    static class SlowClient implements DownstreamClient {
        final CountDownLatch blocker = new CountDownLatch(1);

        @Override
        public String invoke(TenantIdentity identity, String requestId, String payload) {
            try {
                blocker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ok";
        }
    }

    @Test
    void concurrentDeductionIsConsistent() throws Exception {
        TenantRegistry registry = new TenantRegistry();
        registry.register(new Tenant("t-active", TenantStatus.ACTIVE));
        QuotaManager quotaManager = new QuotaManager();
        quotaManager.initTenant("t-active", CAPACITY);
        DegradationController controller = new DegradationController(quotaManager);
        SlowClient client = new SlowClient();
        AdmissionService service = new AdmissionService(registry, new TenantIdentityResolver(),
                quotaManager, controller, client);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger exhausted = new AtomicInteger();
        ConcurrentLinkedQueue<QuotaSnapshot> snapshots = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            String requestId = "req-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    AdmissionResult r = service.handle(requestId,
                            Map.of("x-tenant-id", "t-active"), "p");
                    if (r.admitted()) {
                        admitted.incrementAndGet();
                    } else if (r.rejectReason() == RejectReason.QUOTA_EXHAUSTED) {
                        exhausted.incrementAndGet();
                    }
                    snapshots.add(r.quotaSnapshot());
                } catch (Exception e) {
                    log.error("并发请求异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        // 等待占用达到容量后再放行下游，制造最大在途压力
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            QuotaSnapshot s = quotaManager.snapshot("t-active");
            if (s.inflight() == CAPACITY) {
                break;
            }
            Thread.sleep(5);
        }
        QuotaSnapshot underPressure = quotaManager.snapshot("t-active");
        log.info("压力峰值: used={}, inflight={}, capacity={}",
                underPressure.used(), underPressure.inflight(), underPressure.capacity());
        assertTrue(underPressure.used() + underPressure.inflight() <= CAPACITY,
                "峰值用量超出容量，发生超卖");

        client.blocker.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS), "并发任务未在限定时间内完成");
        pool.shutdown();

        QuotaSnapshot finalSnapshot = quotaManager.snapshot("t-active");
        log.info("并发结束: admitted={}, exhausted={}, final used={}, inflight={}",
                admitted.get(), exhausted.get(), finalSnapshot.used(),
                finalSnapshot.inflight());

        assertEquals(CAPACITY, admitted.get(), "成功数量必须恰好等于容量");
        assertEquals(THREADS - CAPACITY, exhausted.get(), "其余请求必须因配额耗尽被拒");
        assertEquals(CAPACITY, finalSnapshot.used(), "累计扣减不能丢失");
        assertEquals(0L, finalSnapshot.inflight(), "在途必须全部结算");
        assertEquals(admitted.get() + exhausted.get(), THREADS, "结果分类必须完整");

        for (QuotaSnapshot s : snapshots) {
            assertTrue(s.used() + s.inflight() <= CAPACITY,
                    "任何时刻 used+inflight 不得超过容量");
        }
        long maxInFlight = snapshots.stream()
                .mapToLong(s -> s.used() + s.inflight()).max().orElse(0);
        log.info("观测到的最大 used+inflight = {}", maxInFlight);
    }
}

package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.common.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreakerManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发扣减：不丢失、不超卖；以及经 AdmissionService 的端到端并发配额守恒。
 */
@SpringBootTest
class ConcurrencyQuotaTest {

    @Autowired
    private AdmissionService admissionService;
    @Autowired
    private QuotaManager quotaManager;
    @Autowired
    private CircuitBreakerManager breakerManager;

    @org.junit.jupiter.api.BeforeEach
    void resetTenantB() {
        quotaManager.reset("tenant-b");
        breakerManager.reset("tenant-b");
    }

    @Test
    void quotaStateCasNeverOversells() throws Exception {
        com.github.highcumontoa.tenantquotaadmissionjava.quota.TenantQuotaState state =
                new com.github.highcumontoa.tenantquotaadmissionjava.quota.TenantQuotaState(
                        "unit-tenant", 10, 60_000);
        int threads = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger acquired = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (state.tryAcquire()) {
                        acquired.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(10, acquired.get(), "成功扣减数必须恰好等于上限，不能超卖");
        var view = state.snapshot();
        assertEquals(10, view.used() + view.inFlight(), "used+inFlight 必须守恒在 limit");
    }

    @Test
    void concurrentRequestsNeverExceedTenantLimit() throws Exception {
        // tenant-b 上限为 2
        int threads = 30;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<DecisionCode> rejects = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    admissionService.process("tenant-b", null, "token-b",
                            "cc-" + idx, "p");
                    success.incrementAndGet();
                } catch (AdmissionException e) {
                    rejected.incrementAndGet();
                    rejects.add(e.getCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(2, success.get(), "窗口内最多成功 2 个");
        assertEquals(threads - 2, rejected.get(), "其余全部被限流");
        assertTrue(rejects.stream().allMatch(c -> c == DecisionCode.QUOTA_EXHAUSTED),
                "所有拒绝原因必须可区分且为 QUOTA_EXHAUSTED: " + rejects);
    }
}

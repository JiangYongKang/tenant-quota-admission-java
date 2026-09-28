package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreakerManager;
import com.github.highcumontoa.tenantquotaadmissionjava.common.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantConfig;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 首个请求在下游调用前被准入拒绝（如强制降级）时，同 requestId 的并发等待者
 * 必须得到同样可区分的拒绝原因，而不是 CompletionException 包装后的不可解释错误。
 */
@SpringBootTest
@Import(FakeDownstream.Config.class)
class InFlightRejectionTest {

    private static final String T = "tenant-inflight";

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
        registry.register(new TenantConfig(T, true, "tok", 10, 60_000,
                ForcedState.DEGRADED, 1));
    }

    @Test
    void waitersOfRejectedInFlightRequestGetSameDistinguishableCode() throws Exception {
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger degraded = new AtomicInteger();
        AtomicInteger wrong = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    admissionService.process(T, null, "tok", "same-if-id", null);
                    wrong.incrementAndGet();
                } catch (AdmissionException e) {
                    if (e.getCode() == DecisionCode.DEGRADED) {
                        degraded.incrementAndGet();
                    } else {
                        wrong.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(threads, degraded.get(), "所有并发请求都必须是可区分的 DEGRADED");
        assertEquals(0, wrong.get(), "不得出现异常泄漏或其他判定");
        assertEquals(0, downstream.getInvocations(), "降级状态不得调用下游");
    }
}

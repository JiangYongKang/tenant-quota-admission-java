package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreakerManager;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ProcessResult;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.TenantQuotaState;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantConfig;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 配额守恒：返还与保留必须精确，计数器 acquired/released/consumed 满足不变式。
 */
@SpringBootTest
@Import(FakeDownstream.Config.class)
class QuotaConservationTest {

    private static final String T = "tenant-cons";

    @Autowired
    private AdmissionService admissionService;
    @Autowired
    private QuotaManager quotaManager;
    @Autowired
    private CircuitBreakerManager breakerManager;
    @Autowired
    private TenantRegistry registry;
    @Autowired
    private FakeDownstream downstream;

    @BeforeEach
    void setUp() {
        downstream.reset();
        quotaManager.reset(T);
        breakerManager.reset(T);
        registry.register(new TenantConfig(T, true, "tok", 1000, 60_000,
                ForcedState.AUTO, 1));
    }

    @Test
    void refundAndRetainBalanceExactly() {
        TenantQuotaState state = quotaManager.forTenant(T, 1000, 60_000);

        downstream.setMode(FakeDownstream.Mode.SUCCESS);
        admissionService.process(T, null, "tok", "c-1", null);
        downstream.setMode(FakeDownstream.Mode.BIZ_REJECT);
        admissionService.process(T, null, "tok", "c-2", null); // 返还
        downstream.setMode(FakeDownstream.Mode.TIMEOUT);
        admissionService.process(T, null, "tok", "c-3", null); // 保留
        downstream.setMode(FakeDownstream.Mode.INTERNAL_ERROR);
        admissionService.process(T, null, "tok", "c-4", null); // 返还
        downstream.setMode(FakeDownstream.Mode.UNAVAILABLE);
        admissionService.process(T, null, "tok", "c-5", null); // 返还

        var view = quotaManager.snapshot(T);
        assertEquals(0, view.inFlight(), "所有请求结束后在途必须归零");
        assertEquals(2, view.used(), "成功1 + 超时保留1 = 2 个被消耗/保留");
        assertEquals(5, state.getTotalAcquired(), "共占用 5 次");
        assertEquals(5, state.getTotalReleased(), "共释放 5 次（含消耗回落 inFlight）");
        assertEquals(2, state.getTotalConsumed(), "仅成功与超时计入 consumed");
    }

    @Test
    void concurrentSuccessConservesQuota() throws Exception {
        TenantQuotaState state = quotaManager.forTenant(T, 1000, 60_000);
        downstream.setMode(FakeDownstream.Mode.SUCCESS);
        int threads = 100;
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(threads);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(20);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    admissionService.process(T, null, "tok", "cm-" + idx, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();

        var view = quotaManager.snapshot(T);
        assertEquals(0, view.inFlight(), "并发结束后在途必须归零，无丢失计数");
        assertEquals(threads, state.getTotalAcquired(), "占用次数等于请求数");
        assertEquals(threads, state.getTotalReleased(), "释放次数等于请求数");
        assertEquals(threads, view.used(), "全部成功时 used 精确等于请求数，无丢失/超卖");
    }
}

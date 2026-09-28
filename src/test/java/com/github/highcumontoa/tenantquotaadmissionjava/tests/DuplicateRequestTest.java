package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreakerManager;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ProcessResult;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复请求（重试）不能重复扣减：完成后重复 & 在途并发重复两种情形。
 */
@SpringBootTest
@Import(FakeDownstream.Config.class)
class DuplicateRequestTest {

    @Autowired
    private AdmissionService admissionService;
    @Autowired
    private QuotaManager quotaManager;
    @Autowired
    private CircuitBreakerManager breakerManager;
    @Autowired
    private FakeDownstream downstream;

    @BeforeEach
    void setUp() {
        downstream.reset();
        quotaManager.reset("tenant-a");
        breakerManager.reset("tenant-a");
    }

    @Test
    void sameRequestIdAfterCompletionIsIdempotent() {
        ProcessResult first = admissionService.process("tenant-a", null, "token-a",
                "dup-1", "p");
        ProcessResult second = admissionService.process("tenant-a", null, "token-a",
                "dup-1", "p");

        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, first.decision());
        assertTrue(second.duplicate(), "第二次必须标记为重复");
        assertEquals(DecisionCode.DOWNSTREAM_SUCCESS, second.decision(),
                "重复请求返回与首次一致的判定");
        assertEquals(1, downstream.getInvocations(), "下游只能被调用一次");
        assertEquals(1L, quotaManager.snapshot("tenant-a").used(), "只扣减一次配额");
    }

    @Test
    void concurrentSameRequestIdSharesSingleExecution() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        downstream.setGate(entered);

        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger duplicates = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ProcessResult r = admissionService.process("tenant-a", null, "token-a",
                            "dup-race", "p");
                    if (r.duplicate()) {
                        duplicates.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (downstream.getInvocations() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        entered.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, downstream.getInvocations(), "同一 requestId 在途时下游只能被调用一次");
        assertEquals(threads - 1, duplicates.get(), "其余并发请求必须共享首次结果");
        assertEquals(1L, quotaManager.snapshot("tenant-a").used(), "并发重复只扣减一次配额");
    }
}

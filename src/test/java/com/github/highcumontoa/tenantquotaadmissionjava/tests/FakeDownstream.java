package com.github.highcumontoa.tenantquotaadmissionjava.tests;

import com.github.highcumontoa.tenantquotaadmissionjava.downstream.BizRejectedException;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamTimeoutException;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.InternalErrorException;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.ServiceUnavailableException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手写可编程下游（不依赖 Mockito）：替换主配置的 HttpDownstreamClient。
 * 支持成功/四类异常/慢调用，并记录调用次数与并发进入数。
 */
public class FakeDownstream implements DownstreamClient {

    public enum Mode { SUCCESS, BIZ_REJECT, TIMEOUT, INTERNAL_ERROR, UNAVAILABLE, SLOW_SUCCESS }

    private volatile Mode mode = Mode.SUCCESS;
    private final AtomicInteger invocations = new AtomicInteger();
    private final AtomicInteger concurrent = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private volatile CountDownLatch gate;

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public void setGate(CountDownLatch gate) {
        this.gate = gate;
    }

    public int getInvocations() {
        return invocations.get();
    }

    public int getMaxConcurrent() {
        return maxConcurrent.get();
    }

    public void reset() {
        invocations.set(0);
        concurrent.set(0);
        maxConcurrent.set(0);
        gate = null;
        mode = Mode.SUCCESS;
    }

    @Override
    public String invoke(String tenantId, String requestId, String payload) {
        invocations.incrementAndGet();
        int now = concurrent.incrementAndGet();
        maxConcurrent.accumulateAndGet(now, Math::max);
        try {
            CountDownLatch g = gate;
            if (g != null) {
                g.await();
            }
            return switch (mode) {
                case BIZ_REJECT -> throw new BizRejectedException("fake: 业务拒绝");
                case TIMEOUT -> throw new DownstreamTimeoutException("fake: 超时");
                case INTERNAL_ERROR -> throw new InternalErrorException("fake: 内部失败");
                case UNAVAILABLE -> throw new ServiceUnavailableException("fake: 临时不可用");
                default -> "ok";
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("fake: 中断");
        } finally {
            concurrent.decrementAndGet();
        }
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public FakeDownstream fakeDownstream() {
            return new FakeDownstream();
        }
    }
}

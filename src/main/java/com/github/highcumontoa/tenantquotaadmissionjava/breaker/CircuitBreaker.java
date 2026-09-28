package com.github.highcumontoa.tenantquotaadmissionjava.breaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单租户熔断器（非 Spring Bean，由 CircuitBreakerManager 按租户持有）。
 *
 * <p>状态机：
 * <ul>
 *   <li>CLOSED：正常放行；连续失败达到 threshold 后打开；</li>
 *   <li>OPEN：直接降级，不调用下游；冷却 cooldownMillis 后放行一个探测请求；</li>
 *   <li>HALF_OPEN：探测成功 -> CLOSED（计数清零），探测失败 -> 重新 OPEN 并重新计时。</li>
 * </ul>
 * 连续失败语义 + 冷却期保证了短暂波动不会导致状态反复抖动。
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final String tenantId;
    private final int threshold;
    private final long cooldownMillis;

    private int failureCount;
    private boolean open;
    private long openedAt;
    private boolean probeInFlight;

    public CircuitBreaker(String tenantId, int threshold, long cooldownMillis) {
        this.tenantId = tenantId;
        this.threshold = threshold;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * 调用下游前的准入判定。
     */
    public synchronized BreakerState beforeCall() {
        if (!open) {
            return BreakerState.ALLOW;
        }
        long now = System.currentTimeMillis();
        if (now - openedAt < cooldownMillis) {
            return BreakerState.TRIP;
        }
        if (!probeInFlight) {
            probeInFlight = true;
            log.info("熔断进入半开，放行探测请求: tenant={}, failures={}", tenantId, failureCount);
            return BreakerState.HALF_OPEN;
        }
        return BreakerState.TRIP;
    }

    public synchronized void onSuccess() {
        boolean wasOpen = open;
        open = false;
        failureCount = 0;
        probeInFlight = false;
        if (wasOpen) {
            log.info("熔断恢复（探测成功）: tenant={}", tenantId);
        }
    }

    public synchronized void onFailure() {
        failureCount++;
        probeInFlight = false;
        if (open || failureCount >= threshold) {
            open = true;
            openedAt = System.currentTimeMillis();
            log.warn("熔断打开/保持: tenant={}, failures={}, threshold={}, cooldownMillis={}",
                    tenantId, failureCount, threshold, cooldownMillis);
        }
    }

    public synchronized BreakerView snapshot() {
        boolean halfOpen = open && probeInFlight;
        String reason;
        if (!open) {
            reason = "CLOSED";
        } else if (halfOpen) {
            reason = "HALF_OPEN";
        } else {
            long remaining = Math.max(0, cooldownMillis - (System.currentTimeMillis() - openedAt));
            reason = "OPEN(cooldownRemainingMillis=" + remaining + ")";
        }
        return new BreakerView(open, halfOpen, failureCount, reason);
    }
}

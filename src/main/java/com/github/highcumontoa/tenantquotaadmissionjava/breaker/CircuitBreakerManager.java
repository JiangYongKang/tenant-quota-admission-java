package com.github.highcumontoa.tenantquotaadmissionjava.breaker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按租户持有并隔离熔断器实例，租户之间的熔断状态互不影响。
 */
@Component
public class CircuitBreakerManager {

    private final int failureThreshold;
    private final long cooldownMillis;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerManager(
            @Value("${tenant.breaker.failure-threshold:3}") int failureThreshold,
            @Value("${tenant.breaker.cooldown-millis:5000}") long cooldownMillis) {
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    public CircuitBreaker forTenant(String tenantId) {
        return breakers.computeIfAbsent(tenantId,
                id -> new CircuitBreaker(id, failureThreshold, cooldownMillis));
    }

    /**
     * 重置指定租户的熔断状态（管理面/测试隔离用）。
     */
    public void reset(String tenantId) {
        breakers.remove(tenantId);
    }

    public BreakerView snapshot(String tenantId) {
        CircuitBreaker breaker = breakers.get(tenantId);
        return breaker == null ? new BreakerView(false, false, 0, "CLOSED") : breaker.snapshot();
    }
}

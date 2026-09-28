package com.github.highcumontoa.tenantquotaadmissionjava.breaker;

/**
 * 熔断器状态快照（用于判定解释与日志）。
 */
public record BreakerView(boolean open, boolean halfOpen, int failureCount, String reason) {
}

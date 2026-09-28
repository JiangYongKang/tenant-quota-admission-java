package com.github.highcumontoa.tenantquotaadmissionjava.quota;

/**
 * 配额策略的不可变快照。throttlePercent 仅在 THROTTLED 状态下生效（0..100）。
 */
public record QuotaPolicy(QuotaState state, int throttlePercent, long version) {
}

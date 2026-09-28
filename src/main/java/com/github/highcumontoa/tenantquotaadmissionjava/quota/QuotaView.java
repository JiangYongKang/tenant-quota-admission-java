package com.github.highcumontoa.tenantquotaadmissionjava.quota;

/**
 * 配额状态快照（用于判定解释与日志）。
 */
public record QuotaView(long limit, long used, long inFlight, long remaining, long windowResetAtMillis) {
}

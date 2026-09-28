package com.github.highcumontoa.tenantquotaadmissionjava.quota;

/**
 * 配额策略状态：
 * NORMAL 正常放行；THROTTLED 按比例限流；DEGRADED 降级快速失败。
 */
public enum QuotaState {
    NORMAL,
    THROTTLED,
    DEGRADED
}

package com.github.highcumontoa.tenantquotaadmissionjava.common;

/**
 * 管理面为租户强制指定的配额状态。
 */
public enum ForcedState {
    AUTO,
    NORMAL,
    RATE_LIMITED,
    DEGRADED
}

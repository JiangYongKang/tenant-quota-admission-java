package com.github.highcumontoa.tenantquotaadmissionjava.breaker;

/**
 * 熔断器对在途/新请求的准入指令。
 */
public enum BreakerState {
    ALLOW,
    TRIP,
    HALF_OPEN
}

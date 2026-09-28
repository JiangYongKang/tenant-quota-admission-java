package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/** 下游调用结果分类，决定配额返还/保留。 */
public enum DownstreamOutcome {
    SUCCESS,
    BUSINESS_REJECTED,
    TIMEOUT,
    INTERNAL_FAILURE,
    UNAVAILABLE
}

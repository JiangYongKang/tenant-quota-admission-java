package com.github.highcumontoa.tenantquotaadmissionjava.common;

/**
 * 下游调用结束后，对本次已占用配额的处理动作。
 */
public enum QuotaAction {
    CONSUME,
    REFUND,
    RETAIN
}

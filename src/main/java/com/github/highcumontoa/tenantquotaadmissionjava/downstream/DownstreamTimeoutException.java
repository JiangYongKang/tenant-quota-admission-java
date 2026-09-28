package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/** 下游超时：结果不确定，本实现按可重试处理并返还配额。 */
public class DownstreamTimeoutException extends DownstreamException {
    public DownstreamTimeoutException(String message) {
        super(DownstreamOutcome.TIMEOUT, message);
    }
}

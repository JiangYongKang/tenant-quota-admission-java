package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/** 下游临时不可用（熔断/连接失败）：返还配额。 */
public class DownstreamUnavailableException extends DownstreamException {
    public DownstreamUnavailableException(String message) {
        super(DownstreamOutcome.UNAVAILABLE, message);
    }
}

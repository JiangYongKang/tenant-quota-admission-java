package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/** 下游内部失败：服务端已受理可能性低，返还配额。 */
public class DownstreamInternalFailureException extends DownstreamException {
    public DownstreamInternalFailureException(String message) {
        super(DownstreamOutcome.INTERNAL_FAILURE, message);
    }
}

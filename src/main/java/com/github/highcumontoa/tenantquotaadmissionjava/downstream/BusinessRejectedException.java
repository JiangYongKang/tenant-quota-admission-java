package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/** 下游业务拒绝（请求不合法等），配额应当返还。 */
public class BusinessRejectedException extends DownstreamException {
    public BusinessRejectedException(String message) {
        super(DownstreamOutcome.BUSINESS_REJECTED, message);
    }
}

package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/**
 * 下游业务拒绝（4xx 业务语义）。
 */
public class BizRejectedException extends RuntimeException {
    public BizRejectedException(String message) {
        super(message);
    }
}

package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/**
 * 下游调用超时。
 */
public class DownstreamTimeoutException extends RuntimeException {
    public DownstreamTimeoutException(String message) {
        super(message);
    }
}

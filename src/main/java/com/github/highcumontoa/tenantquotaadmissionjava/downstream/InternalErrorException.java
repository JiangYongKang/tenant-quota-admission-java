package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/**
 * 下游内部失败（5xx）。
 */
public class InternalErrorException extends RuntimeException {
    public InternalErrorException(String message) {
        super(message);
    }
}

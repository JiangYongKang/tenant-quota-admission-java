package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/**
 * 下游临时不可用（连接失败/熔断/503）。
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}

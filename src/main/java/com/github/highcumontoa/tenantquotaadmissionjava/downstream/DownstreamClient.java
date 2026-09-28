package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/**
 * 下游调用抽象：成功返回响应体字符串，失败抛出四类可区分异常之一。
 */
public interface DownstreamClient {

    String invoke(String tenantId, String requestId, String payload);
}

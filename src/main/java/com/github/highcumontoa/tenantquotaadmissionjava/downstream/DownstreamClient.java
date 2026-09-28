package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentity;

/** 下游调用客户端。 */
public interface DownstreamClient {

    /** 执行业务调用；成功返回结果，失败抛出 {@link DownstreamException} 子类。 */
    String invoke(TenantIdentity identity, String requestId, String payload);
}

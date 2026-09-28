package com.github.highcumontoa.tenantquotaadmissionjava.tenant;

/** 从请求中解析出的租户身份及其来源标识。 */
public record TenantIdentity(String tenantId, String source) {
}

package com.github.highcumontoa.tenantquotaadmissionjava.tenant;

/** 租户账户：id 与启停状态。 */
public record Tenant(String id, TenantStatus status) {
}

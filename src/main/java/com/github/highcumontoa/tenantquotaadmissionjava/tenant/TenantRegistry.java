package com.github.highcumontoa.tenantquotaadmissionjava.tenant;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 租户账户注册表（内存实现）。租户之间通过 id 键完全隔离。 */
@Component
public class TenantRegistry {

    private static final Logger log = LoggerFactory.getLogger(TenantRegistry.class);

    private final ConcurrentMap<String, Tenant> tenants = new ConcurrentHashMap<>();

    public Optional<Tenant> find(String tenantId) {
        return Optional.ofNullable(tenants.get(tenantId));
    }

    public void register(Tenant tenant) {
        tenants.put(tenant.id(), tenant);
        log.info("租户注册: tenantId={}, status={}", tenant.id(), tenant.status());
    }

    public void updateStatus(String tenantId, TenantStatus status) {
        Tenant existing = tenants.get(tenantId);
        if (existing == null) {
            throw new IllegalArgumentException("未知租户: " + tenantId);
        }
        tenants.put(tenantId, new Tenant(tenantId, status));
        log.info("租户状态变更: tenantId={}, {} -> {}", tenantId, existing.status(), status);
    }
}

package com.github.highcumontoa.tenantquotaadmissionjava.tenant;

import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import org.springframework.stereotype.Component;

/** 启动时为内置租户初始化配额账户（每租户独立、互不影响）。 */
@Component
public class TenantBootstrap {

    public static final long DEFAULT_CAPACITY = 20;

    public TenantBootstrap(TenantRegistry tenantRegistry, QuotaManager quotaManager) {
        tenantRegistry.register(new Tenant("t-active", TenantStatus.ACTIVE));
        tenantRegistry.register(new Tenant("t-other", TenantStatus.ACTIVE));
        tenantRegistry.register(new Tenant("t-disabled", TenantStatus.DISABLED));
        quotaManager.initTenant("t-active", DEFAULT_CAPACITY);
        quotaManager.initTenant("t-other", DEFAULT_CAPACITY);
        quotaManager.initTenant("t-disabled", DEFAULT_CAPACITY);
    }
}

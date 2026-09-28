package com.github.highcumontoa.tenantquotaadmissionjava.bootstrap;

import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantConfig;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 本地验证用的初始租户：
 * <ul>
 *   <li>tenant-a：启用，token=token-a，每 60s 配额 10；</li>
 *   <li>tenant-b：启用，token=token-b，每 60s 配额 2（便于验证限流/隔离）；</li>
 *   <li>tenant-disabled：已停用。</li>
 * </ul>
 */
@Component
public class TenantBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);

    private final TenantRegistry registry;

    public TenantBootstrap(TenantRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(String... args) {
        registry.register(new TenantConfig("tenant-a", true, "token-a",
                10, 60_000, ForcedState.AUTO, 1));
        registry.register(new TenantConfig("tenant-b", true, "token-b",
                2, 60_000, ForcedState.AUTO, 1));
        registry.register(new TenantConfig("tenant-disabled", false, "token-disabled",
                10, 60_000, ForcedState.AUTO, 1));
        log.info("初始租户已加载: tenant-a(limit=10), tenant-b(limit=2), tenant-disabled(enabled=false)");
    }
}

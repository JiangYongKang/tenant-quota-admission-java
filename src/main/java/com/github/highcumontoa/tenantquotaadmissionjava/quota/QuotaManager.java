package com.github.highcumontoa.tenantquotaadmissionjava.quota;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 持有每个租户独立的 {@link QuotaAccount}。租户之间没有任何共享计数器，
 * 一个租户的扣减、切换、降级均不影响其他租户。
 */
@Component
public class QuotaManager {

    private static final Logger log = LoggerFactory.getLogger(QuotaManager.class);

    private final ConcurrentMap<String, QuotaAccount> accounts = new ConcurrentHashMap<>();

    public QuotaAccount initTenant(String tenantId, long capacity) {
        QuotaAccount account = new QuotaAccount(tenantId, capacity);
        QuotaAccount previous = accounts.putIfAbsent(tenantId, account);
        if (previous != null) {
            return previous;
        }
        log.info("配额账户初始化: tenantId={}, capacity={}", tenantId, capacity);
        return account;
    }

    public Optional<QuotaAccount> find(String tenantId) {
        return Optional.ofNullable(accounts.get(tenantId));
    }

    public QuotaAccount accountFor(String tenantId) {
        return find(tenantId)
                .orElseThrow(() -> new IllegalStateException("配额账户未初始化: " + tenantId));
    }

    public void switchPolicy(String tenantId, QuotaPolicy policy) {
        accountFor(tenantId).setPolicy(policy);
    }

    public QuotaSnapshot snapshot(String tenantId) {
        return accountFor(tenantId).snapshot();
    }
}

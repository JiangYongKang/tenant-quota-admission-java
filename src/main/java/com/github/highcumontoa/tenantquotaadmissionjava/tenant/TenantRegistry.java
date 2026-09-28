package com.github.highcumontoa.tenantquotaadmissionjava.tenant;

import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户策略注册表：按租户保存不可变快照，支持运行时切换与版本递增。
 * 读取方拿到的是一致性快照，切换过程不会读到半更新状态。
 */
@Component
public class TenantRegistry {

    private final ConcurrentHashMap<String, TenantConfig> configs = new ConcurrentHashMap<>();

    public Optional<TenantConfig> find(String tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(configs.get(tenantId));
    }

    /**
     * 注册/整体替换租户策略；未显式给出版本号时沿用旧版本+1。
     */
    public TenantConfig register(TenantConfig config) {
        TenantConfig toStore = config;
        TenantConfig old = configs.get(config.tenantId());
        if (config.version() <= 0 && old != null) {
            toStore = new TenantConfig(config.tenantId(), config.enabled(), config.token(),
                    config.quotaLimit(), config.windowMillis(), config.forcedState(), old.version() + 1);
        }
        configs.put(toStore.tenantId(), toStore);
        return toStore;
    }

    /**
     * 运行时切换租户的强制配额状态，其余字段保持不变，版本号递增。
     */
    public TenantConfig switchState(String tenantId, ForcedState state) {
        TenantConfig old = require(tenantId);
        TenantConfig next = new TenantConfig(old.tenantId(), old.enabled(), old.token(),
                old.quotaLimit(), old.windowMillis(), state, old.version() + 1);
        configs.put(tenantId, next);
        return next;
    }

    /**
     * 运行时调整配额上限/窗口长度，版本号递增。
     */
    public TenantConfig updateQuota(String tenantId, long limit, long windowMillis) {
        TenantConfig old = require(tenantId);
        TenantConfig next = new TenantConfig(old.tenantId(), old.enabled(), old.token(),
                limit, windowMillis, old.forcedState(), old.version() + 1);
        configs.put(tenantId, next);
        return next;
    }

    private TenantConfig require(String tenantId) {
        TenantConfig old = configs.get(tenantId);
        if (old == null) {
            throw new IllegalArgumentException("unknown tenant: " + tenantId);
        }
        return old;
    }
}

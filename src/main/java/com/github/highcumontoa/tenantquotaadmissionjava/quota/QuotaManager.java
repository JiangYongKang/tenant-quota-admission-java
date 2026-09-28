package com.github.highcumontoa.tenantquotaadmissionjava.quota;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按租户持有并隔离配额状态。每个租户一个 TenantQuotaState 实例，
 * 一个租户的扣减/返还/窗口重置不触及任何其他租户的状态。
 */
@Component
public class QuotaManager {

    private final ConcurrentHashMap<String, TenantQuotaState> states = new ConcurrentHashMap<>();

    /**
     * 获取租户的配额状态；首次出现时按给定上限/窗口创建。
     */
    public TenantQuotaState forTenant(String tenantId, long limit, long windowMillis) {
        return states.computeIfAbsent(tenantId,
                id -> new TenantQuotaState(id, limit, windowMillis));
    }

    /**
     * 将运行时切换后的策略参数同步到既有状态（立即生效）。
     */
    public void syncConfig(String tenantId, long limit, long windowMillis) {
        TenantQuotaState state = states.get(tenantId);
        if (state != null) {
            state.updateLimit(limit, windowMillis);
        }
    }

    /**
     * 驱动所有租户窗口到期检查。
     */
    public void tickAll() {
        states.values().forEach(TenantQuotaState::tick);
    }

    /**
     * 重置指定租户的配额窗口与全部累计计数（管理面/测试隔离用）。
     */
    public void reset(String tenantId) {
        states.remove(tenantId);
    }

    public QuotaView snapshot(String tenantId) {
        TenantQuotaState state = states.get(tenantId);
        return state == null ? null : state.snapshot();
    }
}

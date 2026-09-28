package com.github.highcumontoa.tenantquotaadmissionjava.tenant;

import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;

/**
 * 单个租户的策略快照（不可变）。
 *
 * @param tenantId        租户标识
 * @param enabled         是否启用
 * @param token           预期凭证（与 X-Tenant-Token 比对）
 * @param quotaLimit      配额窗口内上限
 * @param windowMillis    配额窗口长度（毫秒）
 * @param forcedState     管理面强制状态；AUTO 表示由熔断器/配额自动判定
 * @param version         策略版本号，每次切换递增
 */
public record TenantConfig(
        String tenantId,
        boolean enabled,
        String token,
        long quotaLimit,
        long windowMillis,
        ForcedState forcedState,
        long version
) {
}

package com.github.highcumontoa.tenantquotaadmissionjava.web;

import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import com.github.highcumontoa.tenantquotaadmissionjava.core.AdmissionService;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantConfig;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理面入口：运行时切换租户配额状态 / 调整配额上限。
 * 切换以不可变快照 + 版本号递增加生效，对新准入请求立即一致。
 */
@RestController
public class AdminController {

    private final TenantRegistry registry;
    private final AdmissionService admissionService;

    public AdminController(TenantRegistry registry, AdmissionService admissionService) {
        this.registry = registry;
        this.admissionService = admissionService;
    }

    /**
     * 切换强制状态：NORMAL / RATE_LIMITED / DEGRADED / AUTO。
     */
    @PostMapping("/admin/tenants/{tenantId}/state")
    public Map<String, Object> switchState(@PathVariable String tenantId,
                                           @RequestBody Map<String, String> body) {
        ForcedState state = ForcedState.valueOf(body.get("state"));
        TenantConfig updated = registry.switchState(tenantId, state);
        if (state == ForcedState.NORMAL || state == ForcedState.AUTO) {
            admissionService.syncQuotaConfig(tenantId, updated.quotaLimit(), updated.windowMillis());
        }
        return describe(updated, "状态切换成功");
    }

    /**
     * 调整配额窗口上限/长度。
     */
    @PostMapping("/admin/tenants/{tenantId}/quota")
    public Map<String, Object> updateQuota(@PathVariable String tenantId,
                                           @RequestBody Map<String, Object> body) {
        long limit = Long.parseLong(String.valueOf(body.get("limit")));
        long windowMillis = Long.parseLong(String.valueOf(
                body.getOrDefault("windowMillis", "60000")));
        TenantConfig updated = registry.updateQuota(tenantId, limit, windowMillis);
        admissionService.syncQuotaConfig(tenantId, limit, windowMillis);
        return describe(updated, "配额调整成功");
    }

    private Map<String, Object> describe(TenantConfig c, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", c.tenantId());
        m.put("version", c.version());
        m.put("forcedState", c.forcedState());
        m.put("quotaLimit", c.quotaLimit());
        m.put("windowMillis", c.windowMillis());
        m.put("enabled", c.enabled());
        m.put("message", message);
        return m;
    }
}

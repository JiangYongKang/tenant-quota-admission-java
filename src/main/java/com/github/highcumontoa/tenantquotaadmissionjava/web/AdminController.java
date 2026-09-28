package com.github.highcumontoa.tenantquotaadmissionjava.web;

import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamOutcome;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.SimulatedDownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.DegradationController;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaAccount;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaSnapshot;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaState;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantStatus;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端：运行时切换策略、查看配额快照、注入下游模拟行为。 */
@RestController
public class AdminController {

    private final QuotaManager quotaManager;
    private final DegradationController degradationController;
    private final SimulatedDownstreamClient downstreamClient;
    private final TenantRegistry tenantRegistry;

    public AdminController(QuotaManager quotaManager,
                           DegradationController degradationController,
                           SimulatedDownstreamClient downstreamClient,
                           TenantRegistry tenantRegistry) {
        this.quotaManager = quotaManager;
        this.degradationController = degradationController;
        this.downstreamClient = downstreamClient;
        this.tenantRegistry = tenantRegistry;
    }

    @GetMapping("/admin/quota/{tenantId}")
    public QuotaSnapshot snapshot(@PathVariable String tenantId) {
        return quotaManager.snapshot(tenantId);
    }

    @PostMapping("/admin/policy/{tenantId}")
    public Map<String, Object> switchPolicy(@PathVariable String tenantId,
                                            @RequestParam QuotaState state,
                                            @RequestParam(defaultValue = "0") int throttlePercent) {
        degradationController.manualOverride(tenantId, state, throttlePercent);
        QuotaAccount account = quotaManager.accountFor(tenantId);
        return Map.of("tenantId", tenantId, "policy", account.getPolicy());
    }

    @PostMapping("/admin/downstream/behavior")
    public Map<String, String> setBehavior(@RequestParam String scope,
                                           @RequestParam String key,
                                           @RequestParam DownstreamOutcome outcome) {
        switch (scope) {
            case "request" -> downstreamClient.setRequestOutcome(key, outcome);
            case "tenant" -> downstreamClient.setTenantOutcome(key, outcome);
            default -> throw new IllegalArgumentException("scope 必须是 request 或 tenant");
        }
        return Map.of("scope", scope, "key", key, "outcome", outcome.name());
    }

    @PostMapping("/admin/tenant/{tenantId}/status")
    public Map<String, Object> updateTenantStatus(@PathVariable String tenantId,
                                                  @RequestParam TenantStatus status) {
        tenantRegistry.updateStatus(tenantId, status);
        return Map.of("tenantId", tenantId, "status", status);
    }
}

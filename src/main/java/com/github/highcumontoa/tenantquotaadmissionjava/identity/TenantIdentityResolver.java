package com.github.highcumontoa.tenantquotaadmissionjava.identity;

import com.github.highcumontoa.tenantquotaadmissionjava.common.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantConfig;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 从请求头解析并校验租户身份。
 * 身份来源：
 * <ul>
 *   <li>X-Tenant-Id：应用自报身份（非可信），需配合 X-Tenant-Token 校验；</li>
 *   <li>X-Auth-Tenant：鉴权网关注入的可信身份。</li>
 * </ul>
 * 两个来源同时存在且不一致 = 来源冲突；非可信来源 token 不匹配 = 凭证冲突。
 */
@Component
public class TenantIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantIdentityResolver.class);

    private final TenantRegistry registry;

    public TenantIdentityResolver(TenantRegistry registry) {
        this.registry = registry;
    }

    public TenantConfig resolve(String headerTenant, String authTenant, String tokenHeader) {
        log.info("身份解析输入: X-Tenant-Id='{}', X-Auth-Tenant='{}', tokenProvided={}",
                headerTenant, authTenant, tokenHeader != null && !tokenHeader.isBlank());

        ResolvedIdentity identity = resolveIdentity(headerTenant, authTenant);
        TenantConfig config = registry.find(identity.tenantId())
                .orElseThrow(() -> reject(DecisionCode.TENANT_UNKNOWN, identity,
                        "未知租户: " + identity.tenantId()));

        if (!config.enabled()) {
            throw reject(DecisionCode.TENANT_DISABLED, identity,
                    "租户已停用: " + identity.tenantId());
        }

        // 可信来源（X-Auth-Tenant）直接放行；只要自报头出现，就必须通过 token 校验。
        boolean untrustedHeaderPresent = headerTenant != null && !headerTenant.isBlank();
        if (untrustedHeaderPresent) {
            if (tokenHeader == null || tokenHeader.isBlank()
                    || !constantTimeEquals(tokenHeader, config.token())) {
                throw reject(DecisionCode.TENANT_CREDENTIAL_MISMATCH, identity,
                        "租户凭证缺失或不匹配: " + identity.tenantId());
            }
        }

        log.info("身份解析通过: tenantId='{}', source='{}', policyVersion={}",
                identity.tenantId(), identity.source(), config.version());
        return config;
    }

    private ResolvedIdentity resolveIdentity(String headerTenant, String authTenant) {
        String h = headerTenant == null ? null : headerTenant.trim();
        String a = authTenant == null ? null : authTenant.trim();
        boolean hasH = h != null && !h.isEmpty();
        boolean hasA = a != null && !a.isEmpty();

        if (hasH && hasA && !h.equals(a)) {
            ResolvedIdentity identity = new ResolvedIdentity(h, "X-Tenant-Id", "X-Auth-Tenant");
            throw reject(DecisionCode.TENANT_SOURCE_CONFLICT, identity,
                    "租户身份来源冲突: X-Tenant-Id='" + h + "' 与 X-Auth-Tenant='" + a + "' 不一致");
        }
        if (!hasH && !hasA) {
            ResolvedIdentity identity = new ResolvedIdentity(null, null, null);
            throw reject(DecisionCode.TENANT_MISSING, identity, "请求缺少租户身份头");
        }
        String tenantId = hasH ? h : a;
        String source = hasH && hasA ? "both:X-Tenant-Id=X-Auth-Tenant"
                : (hasH ? "X-Tenant-Id" : "X-Auth-Tenant");
        return new ResolvedIdentity(tenantId, source, null);
    }

    private AdmissionException reject(DecisionCode code, ResolvedIdentity identity, String detail) {
        log.warn("身份解析拒绝: code={}, tenantId='{}', source='{}', conflictSource='{}', detail={}",
                code, identity.tenantId(), identity.source(), identity.conflictSource(), detail);
        return new AdmissionException(code, detail);
    }

    private boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes();
        byte[] y = b.getBytes();
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }
}

package com.github.highcumontoa.tenantquotaadmissionjava.tenant;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 从请求头解析租户身份。
 * 支持两个来源头：X-Tenant-Id（网关鉴权后写入）与 X-Tenant-Token（调用方自带），
 * 头值格式为 {@code tenantId} 或 {@code tenantId:source}。
 * 两个来源同时存在时租户 id 必须一致，否则判定为来源冲突。
 */
@Component
public class TenantIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(TenantIdentityResolver.class);

    public static final String HEADER_TENANT_ID = "x-tenant-id";
    public static final String HEADER_TENANT_TOKEN = "x-tenant-token";

    private record HeaderClaim(String tenantId, String source) {
    }

    public TenantIdentity resolve(Map<String, String> headers, TenantRegistry registry) {
        HeaderClaim fromId = parseClaim(header(headers, HEADER_TENANT_ID), "HEADER_ID");
        HeaderClaim fromToken = parseClaim(header(headers, HEADER_TENANT_TOKEN),
                "HEADER_TOKEN");

        if (fromId == null && fromToken == null) {
            log.warn("身份拒绝: 请求缺少租户身份头");
            throw new AdmissionException(RejectReason.IDENTITY_MISSING, null,
                    "请求缺少租户身份头 (X-Tenant-Id / X-Tenant-Token)");
        }

        HeaderClaim claim = fromId != null ? fromId : fromToken;
        if (fromId != null && fromToken != null && !fromId.tenantId().equals(fromToken.tenantId())) {
            log.warn("身份拒绝: 来源冲突 idHeader={}, tokenHeader={}",
                    fromId.tenantId(), fromToken.tenantId());
            throw new AdmissionException(RejectReason.SOURCE_CONFLICT, fromId.tenantId(),
                    "租户身份来源冲突: X-Tenant-Id=" + fromId.tenantId()
                            + " 与 X-Tenant-Token=" + fromToken.tenantId() + " 不一致");
        }

        String tenantId = claim.tenantId();
        Optional<Tenant> tenant = registry.find(tenantId);
        if (tenant.isEmpty()) {
            log.warn("身份拒绝: 未知租户 tenantId={}", tenantId);
            throw new AdmissionException(RejectReason.IDENTITY_UNKNOWN, tenantId,
                    "未知租户: " + tenantId);
        }
        if (tenant.get().status() == TenantStatus.DISABLED) {
            log.warn("身份拒绝: 租户已停用 tenantId={}", tenantId);
            throw new AdmissionException(RejectReason.TENANT_DISABLED, tenantId,
                    "租户已停用: " + tenantId);
        }

        String source = fromId != null && fromToken != null
                ? fromId.source() + "+" + fromToken.source()
                : claim.source();
        log.debug("身份通过: tenantId={}, source={}", tenantId, source);
        return new TenantIdentity(tenantId, source);
    }

    private HeaderClaim parseClaim(String raw, String defaultSource) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        int sep = trimmed.indexOf(':');
        if (sep < 0) {
            return new HeaderClaim(trimmed, defaultSource);
        }
        return new HeaderClaim(trimmed.substring(0, sep).trim(),
                trimmed.substring(sep + 1).trim());
    }

    /** 大小写不敏感地读取请求头（HTTP 头名本身大小写不敏感）。 */
    private String header(Map<String, String> headers, String name) {
        String direct = headers.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}

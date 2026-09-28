package com.github.highcumontoa.tenantquotaadmissionjava.identity;

/**
 * 身份解析结果。
 *
 * @param tenantId      解析出的租户标识
 * @param source        身份来源头名称
 * @param conflictSource 冲突的另一来源头名称（无冲突时为 null）
 */
public record ResolvedIdentity(String tenantId, String source, String conflictSource) {
}

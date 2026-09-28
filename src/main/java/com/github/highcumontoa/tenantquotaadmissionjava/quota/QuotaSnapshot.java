package com.github.highcumontoa.tenantquotaadmissionjava.quota;

/** 某租户配额账户的只读快照，用于日志与结果解释。 */
public record QuotaSnapshot(String tenantId,
                            long capacity,
                            long used,
                            long inflight,
                            QuotaState state,
                            long policyVersion) {
}

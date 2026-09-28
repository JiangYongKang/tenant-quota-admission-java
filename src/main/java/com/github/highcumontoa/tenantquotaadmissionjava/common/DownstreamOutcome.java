package com.github.highcumontoa.tenantquotaadmissionjava.common;

/**
 * 一次下游调用结束后的结构化结果：判定码 + 配额动作 + 说明。
 */
public record DownstreamOutcome(DecisionCode code, QuotaAction quotaAction, String detail) {
}

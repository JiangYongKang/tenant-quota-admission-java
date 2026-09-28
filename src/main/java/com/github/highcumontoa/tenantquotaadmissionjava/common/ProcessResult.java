package com.github.highcumontoa.tenantquotaadmissionjava.common;

import java.util.Map;

/**
 * 请求处理的最终结果（成功、下游拒绝/异常、重复请求等）。
 */
public record ProcessResult(
        String requestId,
        String tenantId,
        boolean admitted,
        boolean duplicate,
        DecisionCode decision,
        String detail,
        Map<String, Object> explanation
) {
}

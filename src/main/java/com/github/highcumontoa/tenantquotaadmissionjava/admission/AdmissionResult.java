package com.github.highcumontoa.tenantquotaadmissionjava.admission;

import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaSnapshot;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.RejectReason;

/** 准入与下游调用的最终结果，包含完整解释信息。 */
public record AdmissionResult(boolean admitted,
                              String requestId,
                              String tenantId,
                              RejectReason rejectReason,
                              boolean quotaRefunded,
                              String downstreamResponse,
                              QuotaSnapshot quotaSnapshot,
                              String decisionExplanation) {
}

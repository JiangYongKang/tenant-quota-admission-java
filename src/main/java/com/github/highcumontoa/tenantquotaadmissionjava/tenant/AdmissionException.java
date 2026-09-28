package com.github.highcumontoa.tenantquotaadmissionjava.tenant;

/** 准入被拒绝时抛出，携带可区分原因与租户上下文。 */
public class AdmissionException extends RuntimeException {

    private final RejectReason reason;
    private final String tenantId;

    public AdmissionException(RejectReason reason, String tenantId, String message) {
        super(message);
        this.reason = reason;
        this.tenantId = tenantId;
    }

    public RejectReason getReason() {
        return reason;
    }

    public String getTenantId() {
        return tenantId;
    }
}

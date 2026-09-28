package com.github.highcumontoa.tenantquotaadmissionjava.common;

/**
 * 准入阶段的可解释拒绝异常（身份/配额/降级）。
 */
public class AdmissionException extends RuntimeException {
    private final DecisionCode code;

    public AdmissionException(DecisionCode code, String message) {
        super(message);
        this.code = code;
    }

    public DecisionCode getCode() {
        return code;
    }
}

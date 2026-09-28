package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

/** 下游调用异常基类：携带结果分类。 */
public class DownstreamException extends RuntimeException {

    private final DownstreamOutcome outcome;

    public DownstreamException(DownstreamOutcome outcome, String message) {
        super(message);
        this.outcome = outcome;
    }

    public DownstreamException(DownstreamOutcome outcome, String message, Throwable cause) {
        super(message, cause);
        this.outcome = outcome;
    }

    public DownstreamOutcome getOutcome() {
        return outcome;
    }
}

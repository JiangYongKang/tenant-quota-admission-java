package com.github.highcumontoa.tenantquotaadmissionjava.admission;

import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamException;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamOutcome;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.DegradationController;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaAccount;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaSnapshot;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.RejectReason;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentity;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentityResolver;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantRegistry;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 准入编排。
 *
 * <p>判定顺序：身份校验 → requestId 幂等/限流降级闸门 → 配额占用 → 下游调用 → 结算：
 * <ul>
 *   <li>下游成功：占用转正式用量（不返还）；</li>
 *   <li>业务拒绝/超时/内部失败/临时不可用：返还占用（在本系统语义下均视为可重试）；</li>
 *   <li>闸门拒绝（耗尽/限流/降级）：从未占用，无返还；</li>
 *   <li>重复请求：回放首次终态，不再次扣减也不再次调用下游。</li>
 * </ul>
 */
@Service
public class AdmissionService {

    private static final Logger log = LoggerFactory.getLogger(AdmissionService.class);

    private final TenantRegistry tenantRegistry;
    private final TenantIdentityResolver identityResolver;
    private final QuotaManager quotaManager;
    private final DegradationController degradationController;
    private final DownstreamClient downstreamClient;

    public AdmissionService(TenantRegistry tenantRegistry,
                            TenantIdentityResolver identityResolver,
                            QuotaManager quotaManager,
                            DegradationController degradationController,
                            DownstreamClient downstreamClient) {
        this.tenantRegistry = tenantRegistry;
        this.identityResolver = identityResolver;
        this.quotaManager = quotaManager;
        this.degradationController = degradationController;
        this.downstreamClient = downstreamClient;
    }

    public AdmissionResult handle(String requestId, Map<String, String> headers, String payload) {
        log.info("准入开始: requestId={}, headers={}, payload={}", requestId, headers, payload);

        TenantIdentity identity = identityResolver.resolve(headers, tenantRegistry);
        String tenantId = identity.tenantId();
        QuotaAccount account = quotaManager.accountFor(tenantId);

        QuotaAccount.JoinResult join = account.join(requestId);
        QuotaAccount.Ledger ledger = join.ledger();

        if (join.status() == QuotaAccount.JoinStatus.REPLAY) {
            QuotaAccount.RequestOutcome replayed = join.outcome();
            log.info("判定结果(重复回放): requestId={}, tenantId={}, acquired={}, success={}, reason={}",
                    requestId, tenantId, replayed.acquired(), replayed.success(),
                    replayed.rejectReason());
            return toResult(requestId, tenantId, replayed, true);
        }
        if (join.status() == QuotaAccount.JoinStatus.GATE_REJECTED) {
            QuotaAccount.RequestOutcome rejected = join.outcome();
            log.info("判定结果(闸门拒绝): requestId={}, tenantId={}, reason={}",
                    requestId, tenantId, rejected.rejectReason());
            return toResult(requestId, tenantId, rejected, false);
        }

        // leader 且已占用（未在闸门处被拒）
        try {
            String response = downstreamClient.invoke(identity, requestId, payload);
            account.commit(ledger, response);
            degradationController.evaluate(tenantId, DownstreamOutcome.SUCCESS);
            QuotaAccount.RequestOutcome outcome = ledger.outcome();
            log.info("判定结果(成功): requestId={}, tenantId={}, used={}",
                    requestId, tenantId, outcome.snapshot().used());
            return toResult(requestId, tenantId, outcome, false);
        } catch (DownstreamException e) {
            RejectReason reason = mapReason(e.getOutcome());
            account.refund(ledger, reason);
            degradationController.evaluate(tenantId, e.getOutcome());
            QuotaAccount.RequestOutcome outcome = ledger.outcome();
            log.warn("判定结果(下游失败已返还): requestId={}, tenantId={}, outcome={}, reason={}, inflight={}",
                    requestId, tenantId, e.getOutcome(), reason, outcome.snapshot().inflight());
            return toResult(requestId, tenantId, outcome, false);
        } catch (RuntimeException e) {
            account.refund(ledger, RejectReason.DOWNSTREAM_UNCLASSIFIED);
            degradationController.evaluate(tenantId, DownstreamOutcome.INTERNAL_FAILURE);
            log.error("判定结果(未分类异常，已返还): requestId={}, tenantId={}",
                    requestId, tenantId, e);
            return toResult(requestId, tenantId, ledger.outcome(), false);
        }
    }

    private RejectReason mapReason(DownstreamOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> null;
            case BUSINESS_REJECTED -> RejectReason.DOWNSTREAM_BUSINESS_REJECTED;
            case TIMEOUT -> RejectReason.DOWNSTREAM_TIMEOUT;
            case INTERNAL_FAILURE -> RejectReason.DOWNSTREAM_INTERNAL_FAILURE;
            case UNAVAILABLE -> RejectReason.DOWNSTREAM_UNAVAILABLE;
        };
    }

    private AdmissionResult toResult(String requestId, String tenantId,
                                     QuotaAccount.RequestOutcome outcome,
                                     boolean replayed) {
        String explanation;
        if (!outcome.acquired()) {
            explanation = switch (outcome.rejectReason()) {
                case QUOTA_EXHAUSTED -> "配额已满，未占用配额";
                case THROTTLED_BY_POLICY -> "命中限流策略，未占用配额";
                case DEGRADED_BY_POLICY -> "租户处于降级状态，快速失败，未占用配额";
                default -> "准入被拒绝: " + outcome.rejectReason();
            };
        } else if (outcome.success()) {
            explanation = (replayed ? "重复请求回放首次成功结果，未重复扣减"
                    : "下游成功，占用配额转为正式用量，不返还");
        } else {
            explanation = (replayed ? "重复请求回放首次失败结果，未重复扣减"
                    : "下游结果=" + outcome.rejectReason() + "，按规则释放占用并返还配额");
        }
        QuotaSnapshot snapshot = outcome.snapshot();
        return new AdmissionResult(outcome.acquired() && outcome.success(),
                requestId, tenantId,
                outcome.success() ? null : outcome.rejectReason(),
                outcome.refunded(),
                outcome.response(),
                snapshot,
                explanation + (replayed ? "（幂等回放）" : ""));
    }
}

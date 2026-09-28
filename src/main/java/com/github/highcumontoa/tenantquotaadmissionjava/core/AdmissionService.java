package com.github.highcumontoa.tenantquotaadmissionjava.core;

import com.github.highcumontoa.tenantquotaadmissionjava.breaker.BreakerState;
import com.github.highcumontoa.tenantquotaadmissionjava.breaker.BreakerView;
import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreaker;
import com.github.highcumontoa.tenantquotaadmissionjava.breaker.CircuitBreakerManager;
import com.github.highcumontoa.tenantquotaadmissionjava.common.AdmissionException;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DecisionCode;
import com.github.highcumontoa.tenantquotaadmissionjava.common.DownstreamOutcome;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ForcedState;
import com.github.highcumontoa.tenantquotaadmissionjava.common.ProcessResult;
import com.github.highcumontoa.tenantquotaadmissionjava.common.QuotaAction;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.BizRejectedException;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamClient;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.InternalErrorException;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.ServiceUnavailableException;
import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamTimeoutException;
import com.github.highcumontoa.tenantquotaadmissionjava.identity.TenantIdentityResolver;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaManager;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.QuotaView;
import com.github.highcumontoa.tenantquotaadmissionjava.quota.TenantQuotaState;
import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求准入与执行编排。
 *
 * <p>处理流程：
 * <ol>
 *   <li>身份解析与校验（可区分拒绝）；</li>
 *   <li>按 (租户, requestId) 对在途/近期完成请求去重，重试只观察首次结果，绝不重复扣减；</li>
 *   <li>基于一致性策略快照判定状态：强制状态（管理面） &gt; 熔断器 &gt; 配额；</li>
 *   <li>原子 CAS 占用配额后调用下游；</li>
 *   <li>按下游结果类型返还/保留配额并更新熔断器，返回可解释结果。</li>
 * </ol>
 * 每个租户的策略、配额、熔断器、去重表均为独立实例，互不可见。
 */
@Service
public class AdmissionService {

    private static final Logger log = LoggerFactory.getLogger(AdmissionService.class);

    private final TenantIdentityResolver identityResolver;
    private final QuotaManager quotaManager;
    private final CircuitBreakerManager breakerManager;
    private final DownstreamClient downstreamClient;
    private final long dedupMillis;
    private final AtomicLong idSequence = new AtomicLong();

    /** key = tenantId + '#' + requestId；按租户隔离去重空间。 */
    private final ConcurrentHashMap<String, CompletableFuture<ProcessResult>> inFlight =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletedEntry> completed = new ConcurrentHashMap<>();

    public AdmissionService(TenantIdentityResolver identityResolver,
                            QuotaManager quotaManager,
                            CircuitBreakerManager breakerManager,
                            DownstreamClient downstreamClient,
                            @Value("${tenant.dedup.millis:60000}") long dedupMillis) {
        this.identityResolver = identityResolver;
        this.quotaManager = quotaManager;
        this.breakerManager = breakerManager;
        this.downstreamClient = downstreamClient;
        this.dedupMillis = dedupMillis;
    }

    public ProcessResult process(String headerTenant,
                                 String authTenant,
                                 String tokenHeader,
                                 String requestId,
                                 String payload) {
        // 身份失败不进入去重表：客户端修正身份后应能重试。
        TenantConfig config = identityResolver.resolve(headerTenant, authTenant, tokenHeader);
        String tenantId = config.tenantId();
        String effectiveRequestId = (requestId == null || requestId.isBlank())
                ? "auto-" + idSequence.incrementAndGet() : requestId;
        String dedupKey = tenantId + "#" + effectiveRequestId;

        ProcessResult completedResult = checkCompleted(dedupKey);
        if (completedResult != null) {
            return completedResult;
        }

        CompletableFuture<ProcessResult> newFuture = new CompletableFuture<>();
        CompletableFuture<ProcessResult> existing = inFlight.putIfAbsent(dedupKey, newFuture);
        if (existing != null) {
            // 与在途首次请求同生共死：成功则共享结果；首次被准入拒绝时抛出同样可区分的原因，
            // 避免 CompletionException 包装后退化为不可解释的 500。
            try {
                ProcessResult shared = existing.join();
                return asDuplicate(shared, effectiveRequestId, tenantId);
            } catch (java.util.concurrent.CompletionException ce) {
                Throwable cause = ce.getCause();
                if (cause instanceof AdmissionException ae) {
                    throw ae;
                }
                throw ce;
            }
        }

        try {
            ProcessResult result = doProcess(config, effectiveRequestId, payload);
            newFuture.complete(result);
            // 仅对真正进入执行链（占用过配额/降级）的结果做幂等记忆
            completed.put(dedupKey, new CompletedEntry(result, System.currentTimeMillis() + dedupMillis));
            return result;
        } catch (AdmissionException e) {
            newFuture.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(dedupKey, newFuture);
        }
    }

    private ProcessResult doProcess(TenantConfig config, String requestId, String payload) {
        String tenantId = config.tenantId();
        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("tenantId", tenantId);
        explanation.put("requestId", requestId);
        explanation.put("policyVersion", config.version());

        // 1. 管理面强制状态（NORMAL 表示显式覆盖熔断/配额的放行强制，仅在演示/管控场景使用）
        ForcedState forced = config.forcedState();
        if (forced == ForcedState.DEGRADED) {
            return rejectDegraded(config, requestId, explanation, "管理面强制降级", "FORCED_DEGRADED");
        }
        if (forced == ForcedState.RATE_LIMITED) {
            return rejectQuota(config, requestId, explanation, "管理面强制限流", "FORCED_RATE_LIMITED");
        }

        // 2. 熔断器（自动降级；成功探测后自动恢复，冷却期防抖动）
        CircuitBreaker breaker = breakerManager.forTenant(tenantId);
        BreakerState breakerState = breaker.beforeCall();
        BreakerView breakerView = breaker.snapshot();
        explanation.put("breaker", breakerView.reason() + "(" + breakerView.failureCount() + ")");
        if (breakerState == BreakerState.TRIP) {
            return rejectDegraded(config, requestId, explanation,
                    "熔断器打开，临时不可用，降级保护", "BREAKER_OPEN");
        }

        // 3. 配额（固定窗口原子扣减）
        TenantQuotaState quota = quotaManager.forTenant(tenantId, config.quotaLimit(), config.windowMillis());
        QuotaView before = quota.snapshot();
        explanation.put("quotaBefore", view(before));
        if (!quota.tryAcquire()) {
            return rejectQuota(config, requestId, explanation, "租户窗口配额已用尽", "QUOTA_EXHAUSTED");
        }

        // 4. 调用下游并按结果决定配额动作
        DownstreamOutcome outcome;
        String downstreamBody = null;
        try {
            downstreamBody = downstreamClient.invoke(tenantId, requestId, payload);
            breaker.onSuccess();
            outcome = new DownstreamOutcome(DecisionCode.DOWNSTREAM_SUCCESS, QuotaAction.CONSUME,
                    "下游处理成功");
        } catch (BizRejectedException e) {
            breaker.onSuccess(); // 业务拒绝不代表下游不健康
            outcome = new DownstreamOutcome(DecisionCode.DOWNSTREAM_BIZ_REJECTED, QuotaAction.REFUND,
                    e.getMessage());
        } catch (DownstreamTimeoutException e) {
            breaker.onFailure();
            outcome = new DownstreamOutcome(DecisionCode.DOWNSTREAM_TIMEOUT, QuotaAction.RETAIN,
                    e.getMessage());
        } catch (InternalErrorException e) {
            breaker.onFailure();
            outcome = new DownstreamOutcome(DecisionCode.DOWNSTREAM_INTERNAL_ERROR, QuotaAction.REFUND,
                    e.getMessage());
        } catch (ServiceUnavailableException e) {
            breaker.onFailure();
            outcome = new DownstreamOutcome(DecisionCode.DOWNSTREAM_UNAVAILABLE, QuotaAction.REFUND,
                    e.getMessage());
        }

        boolean consumed = outcome.quotaAction() != QuotaAction.REFUND;
        quota.release(consumed);
        QuotaView after = quota.snapshot();
        explanation.put("quotaAfter", view(after));
        explanation.put("quotaAction", outcome.quotaAction().name());
        explanation.put("breakerAfter", breaker.snapshot().reason());
        explanation.put("downstream", outcome.code().name());
        if (downstreamBody != null) {
            explanation.put("body", downstreamBody);
        }

        ProcessResult result = new ProcessResult(requestId, tenantId, true, false,
                outcome.code(), outcome.detail(), Map.copyOf(explanation));
        log.info("请求处理完成: tenant={}, requestId={}, decision={}, quotaAction={}, detail={}",
                tenantId, requestId, outcome.code(), outcome.quotaAction(), outcome.detail());
        return result;
    }

    private ProcessResult rejectDegraded(TenantConfig config, String requestId,
                                         Map<String, Object> explanation, String detail, String trigger) {
        explanation.put("effectiveState", "DEGRADED");
        explanation.put("trigger", trigger);
        log.warn("准入拒绝-降级: tenant={}, requestId={}, trigger={}, detail={}",
                config.tenantId(), requestId, trigger, detail);
        throw new AdmissionException(DecisionCode.DEGRADED, detail + " | " + trigger);
    }

    private ProcessResult rejectQuota(TenantConfig config, String requestId,
                                      Map<String, Object> explanation, String detail, String trigger) {
        QuotaView view = quotaManager.snapshot(config.tenantId());
        explanation.put("effectiveState", "RATE_LIMITED");
        explanation.put("trigger", trigger);
        if (view != null) {
            explanation.put("quota", view(view));
        }
        log.warn("准入拒绝-限流: tenant={}, requestId={}, trigger={}, quota={}, detail={}",
                config.tenantId(), requestId, trigger, view, detail);
        throw new AdmissionException(DecisionCode.QUOTA_EXHAUSTED, detail + " | " + trigger);
    }

    private Map<String, Object> view(QuotaView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limit", v.limit());
        m.put("used", v.used());
        m.put("inFlight", v.inFlight());
        m.put("remaining", v.remaining());
        m.put("windowResetAt", v.windowResetAtMillis());
        return m;
    }

    private ProcessResult asDuplicate(ProcessResult first, String requestId, String tenantId) {
        log.info("重复请求命中在途结果: tenant={}, requestId={}, firstDecision={}",
                tenantId, requestId, first.decision());
        Map<String, Object> explanation = new LinkedHashMap<>(first.explanation());
        explanation.put("duplicate", true);
        return new ProcessResult(requestId, tenantId, first.admitted(), true,
                first.decision(), "重复请求（与在途首次请求共享结果，未重复扣减）", Map.copyOf(explanation));
    }

    private ProcessResult checkCompleted(String dedupKey) {
        CompletedEntry entry = completed.get(dedupKey);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt) {
            completed.remove(dedupKey, entry);
            return null;
        }
        ProcessResult first = entry.result();
        log.info("重复请求命中近期完成结果: key={}, firstDecision={}", dedupKey, first.decision());
        Map<String, Object> explanation = new LinkedHashMap<>(first.explanation());
        explanation.put("duplicate", true);
        return new ProcessResult(first.requestId(), first.tenantId(), first.admitted(), true,
                first.decision(), "重复请求（复用首次结果，未重复扣减）", Map.copyOf(explanation));
    }

    /** 供管理面在切换策略后同步配额参数。 */
    public void syncQuotaConfig(String tenantId, long limit, long windowMillis) {
        quotaManager.syncConfig(tenantId, limit, windowMillis);
    }

    private record CompletedEntry(ProcessResult result, long expiresAt) {
    }
}

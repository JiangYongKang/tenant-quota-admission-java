package com.github.highcumontoa.tenantquotaadmissionjava.downstream;

import com.github.highcumontoa.tenantquotaadmissionjava.tenant.TenantIdentity;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 可配置行为的下游客户端：按 key（{@code req:requestId} 或 {@code tenant:tenantId}，
 * 前者优先）返回 {@link DownstreamOutcome}；SUCCESS 返回响应，其余抛出对应异常。
 * 默认全部成功。
 */
@Component
public class SimulatedDownstreamClient implements DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDownstreamClient.class);

    private final ConcurrentMap<String, Function<String, DownstreamOutcome>> behaviors =
            new ConcurrentHashMap<>();

    public void setBehavior(String behaviorKey, Function<String, DownstreamOutcome> behavior) {
        behaviors.put(behaviorKey, behavior);
    }

    public void setRequestOutcome(String requestId, DownstreamOutcome outcome) {
        setBehavior("req:" + requestId, r -> outcome);
    }

    public void setTenantOutcome(String tenantId, DownstreamOutcome outcome) {
        setBehavior("tenant:" + tenantId, r -> outcome);
    }

    public void clear() {
        behaviors.clear();
    }

    @Override
    public String invoke(TenantIdentity identity, String requestId, String payload) {
        Function<String, DownstreamOutcome> behavior =
                behaviors.getOrDefault("req:" + requestId,
                        behaviors.getOrDefault("tenant:" + identity.tenantId(),
                                r -> DownstreamOutcome.SUCCESS));
        DownstreamOutcome outcome = behavior.apply(requestId);
        log.info("下游调用: tenantId={}, requestId={}, outcome={}",
                identity.tenantId(), requestId, outcome);
        switch (outcome) {
            case SUCCESS -> {
                return "ok:" + requestId;
            }
            case BUSINESS_REJECTED -> throw new BusinessRejectedException(
                    "下游业务拒绝: " + requestId);
            case TIMEOUT -> throw new DownstreamTimeoutException(
                    "下游超时: " + requestId);
            case INTERNAL_FAILURE -> throw new DownstreamInternalFailureException(
                    "下游内部失败: " + requestId);
            case UNAVAILABLE -> throw new DownstreamUnavailableException(
                    "下游临时不可用: " + requestId);
            default -> throw new DownstreamException(DownstreamOutcome.INTERNAL_FAILURE,
                    "未分类的下游结果: " + requestId);
        }
    }
}

package com.github.highcumontoa.tenantquotaadmissionjava.quota;

import com.github.highcumontoa.tenantquotaadmissionjava.tenant.RejectReason;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个租户的配额账户。
 *
 * <p>并发模型：
 * <ul>
 *   <li>{@code used}（已完成并计入）与 {@code inflight}（已占用未结算）在对象锁内变更，
 *       恒有 {@code used + inflight <= capacity}，保证不超卖；</li>
 *   <li>同一 requestId 只有一个 leader 真正执行，重复请求在 leader 的终态上等待并回放结果，
 *       因此重试不会重复扣减；</li>
 *   <li>策略为不可变慢照（volatile），切换对后续准入立即生效，且不影响在途请求的结算。</li>
 * </ul>
 */
public class QuotaAccount {

    private static final Logger log = LoggerFactory.getLogger(QuotaAccount.class);

    /** 一次请求在配额账户上的终态记录。 */
    public record RequestOutcome(boolean acquired,
                                 boolean success,
                                 boolean leader,
                                 RejectReason rejectReason,
                                 boolean refunded,
                                 String response,
                                 QuotaSnapshot snapshot) {
    }

    /** 一次请求的参与凭证：leader 负责执行与结算，follower 等待并回放。 */
    public static final class Ledger {
        private final String requestId;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<RequestOutcome> outcome = new AtomicReference<>();

        Ledger(String requestId) {
            this.requestId = requestId;
        }

        boolean settle(RequestOutcome value) {
            boolean won = outcome.compareAndSet(null, value);
            if (won) {
                done.countDown();
            }
            return won;
        }

        RequestOutcome await() {
            try {
                if (!done.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待同 requestId 在途请求超时: " + requestId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待在途请求被中断: " + requestId, e);
            }
            return outcome.get();
        }

        public boolean isSettled() {
            return outcome.get() != null;
        }

        public RequestOutcome outcome() {
            return outcome.get();
        }
    }

    private final String tenantId;
    private final long capacity;
    private long used;
    private long inflight;

    private volatile QuotaPolicy policy = new QuotaPolicy(QuotaState.NORMAL, 0, 1);
    private final ConcurrentHashMap<String, Ledger> ledgers = new ConcurrentHashMap<>();

    public QuotaAccount(String tenantId, long capacity) {
        this.tenantId = tenantId;
        this.capacity = capacity;
    }

    public String getTenantId() {
        return tenantId;
    }

    public QuotaPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(QuotaPolicy next) {
        QuotaPolicy old = this.policy;
        this.policy = next;
        log.info("策略切换: tenantId={}, {} -> {}, throttlePercent={}, version={}",
                tenantId, old.state(), next.state(), next.throttlePercent(), next.version());
    }

    /** join 的处理状态：需要执行下游、已在闸门被拒、或回放已有终态。 */
    public enum JoinStatus {
        EXECUTE,
        GATE_REJECTED,
        REPLAY
    }

    /** join 的结果：ledger、状态与（回放时的）终态。 */
    public record JoinResult(Ledger ledger, JoinStatus status, RequestOutcome outcome) {

        boolean fresh() {
            return status != JoinStatus.REPLAY;
        }
    }

    /**
     * 加入某个 requestId 的处理：
     * <ul>
     *   <li>首个请求且通过闸门：EXECUTE，由调用方执行下游并结算；</li>
     *   <li>首个请求在闸门处被拒（限流/降级/耗尽）：GATE_REJECTED，终态已生成；</li>
     *   <li>重复请求（含同一线程重试）：REPLAY，等待/读取首次终态，不触碰计数器。</li>
     * </ul>
     */
    public JoinResult join(String requestId) {
        Ledger mine = new Ledger(requestId);
        Ledger existing = ledgers.putIfAbsent(requestId, mine);
        if (existing != null) {
            log.info("重复请求到达，等待首次请求终态: tenantId={}, requestId={}",
                    tenantId, requestId);
            existing.await();
            return new JoinResult(existing, JoinStatus.REPLAY, existing.outcome());
        }
        runGate(mine);
        if (mine.outcome() == null) {
            return new JoinResult(mine, JoinStatus.EXECUTE, null);
        }
        return new JoinResult(mine, JoinStatus.GATE_REJECTED, mine.outcome());
    }

    private void runGate(Ledger ledger) {
        QuotaPolicy current = policy;
        switch (current.state()) {
            case DEGRADED -> {
                log.warn("准入拒绝(降级): tenantId={}, requestId={}, version={}",
                        tenantId, ledger.requestId, current.version());
                ledger.settle(new RequestOutcome(false, false, true,
                        RejectReason.DEGRADED_BY_POLICY, false, null, snapshot()));
                return;
            }
            case THROTTLED -> {
                int bucket = Math.floorMod(ledger.requestId.hashCode(), 100);
                if (bucket < current.throttlePercent()) {
                    log.warn("准入拒绝(限流): tenantId={}, requestId={}, bucket={}/100, percent={}",
                            tenantId, ledger.requestId, bucket, current.throttlePercent());
                    ledger.settle(new RequestOutcome(false, false, true,
                            RejectReason.THROTTLED_BY_POLICY, false, null, snapshot()));
                    return;
                }
            }
            case NORMAL -> {
            }
        }
        if (!tryReserve()) {
            log.warn("准入拒绝(配额耗尽): tenantId={}, requestId={}, used={}, capacity={}",
                    tenantId, ledger.requestId, used, capacity);
            ledger.settle(new RequestOutcome(false, false, true,
                    RejectReason.QUOTA_EXHAUSTED, false, null, snapshot()));
            return;
        }
        log.debug("配额占用成功: tenantId={}, requestId={}, used={}, inflight={}",
                tenantId, ledger.requestId, used, inflight);
    }

    private synchronized boolean tryReserve() {
        if (used + inflight >= capacity) {
            return false;
        }
        inflight++;
        return true;
    }

    /** 下游成功：占用转为正式用量。 */
    public synchronized void commit(Ledger ledger, String response) {
        inflight--;
        used++;
        boolean first = ledger.settle(new RequestOutcome(true, true, true, null, false,
                response, snapshot()));
        if (!first) {
            throw new IllegalStateException("ledger 已终态，不能重复提交: " + ledger.requestId);
        }
        log.info("配额提交: tenantId={}, requestId={}, used={}, inflight={}",
                tenantId, ledger.requestId, used, inflight);
    }

    /** 下游失败按策略需要返还：释放占用，used 不变。 */
    public synchronized void refund(Ledger ledger, RejectReason reason) {
        inflight--;
        boolean first = ledger.settle(new RequestOutcome(true, false, true, reason, true,
                null, snapshot()));
        if (!first) {
            throw new IllegalStateException("ledger 已终态，不能重复返还: " + ledger.requestId);
        }
        log.info("配额返还: tenantId={}, requestId={}, reason={}, used={}, inflight={}",
                tenantId, ledger.requestId, reason, used, inflight);
    }

    public synchronized QuotaSnapshot snapshot() {
        return new QuotaSnapshot(tenantId, capacity, used, inflight,
                policy.state(), policy.version());
    }

    /** 仅供诊断：当前保留的 requestId ledger 数量。 */
    public int ledgerCount() {
        return ledgers.size();
    }
}

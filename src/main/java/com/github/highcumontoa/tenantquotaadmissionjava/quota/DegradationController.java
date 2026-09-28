package com.github.highcumontoa.tenantquotaadmissionjava.quota;

import com.github.highcumontoa.tenantquotaadmissionjava.downstream.DownstreamOutcome;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 根据下游结果滑动窗口失败率驱动 NORMAL &lt;-&gt; DEGRADED 状态机。
 *
 * <p>防抖动设计：
 * <ul>
 *   <li>迟滞阈值：降级阈值（默认 0.5）显著高于恢复阈值（默认 0.25）；</li>
 *   <li>冷却时间（默认 3s）：两次状态切换之间必须间隔，短暂波动被吸收；</li>
 *   <li>需要足够样本（默认 4 个）才允许判定，避免冷启动误判。</li>
 * </ul>
 */
@Component
public class DegradationController {

    private static final Logger log = LoggerFactory.getLogger(DegradationController.class);

    private static final int WINDOW_SIZE = 10;
    private static final int MIN_SAMPLES = 4;
    private static final double DEGRADE_RATIO = 0.5;
    private static final double RECOVER_RATIO = 0.25;
    private static final long COOLDOWN_MS = 3_000L;

    private final QuotaManager quotaManager;

    /** 带时间戳的观测样本。 */
    private record Sample(long timestamp, boolean failed) {
    }

    private record TenantState(Deque<Sample> window,
                               QuotaState currentState,
                               long lastTransitionMillis) {
    }

    private final ConcurrentMap<String, TenantState> states = new ConcurrentHashMap<>();

    public DegradationController(QuotaManager quotaManager) {
        this.quotaManager = quotaManager;
    }

    /** 登记一次下游结果，返回该租户当前应处状态；若发生切换则同步配额策略。 */
    public QuotaState evaluate(String tenantId, DownstreamOutcome outcome) {
        boolean failed = outcome != DownstreamOutcome.SUCCESS;
        TenantState state = states.compute(tenantId, (id, previous) -> {
            TenantState s = previous != null ? previous
                    : new TenantState(new ArrayDeque<>(), QuotaState.NORMAL, 0L);
            synchronized (s) {
                long now = System.currentTimeMillis();
                Deque<Sample> window = new ArrayDeque<>(s.window());
                window.addLast(new Sample(now, failed));
                // 淘汰超过一个冷却窗口的陈旧样本，避免旧失败在恢复迹象出现后引发抖动
                while (!window.isEmpty()
                        && now - window.peekFirst().timestamp() > COOLDOWN_MS) {
                    window.removeFirst();
                }
                while (window.size() > WINDOW_SIZE) {
                    window.removeFirst();
                }
                int samples = window.size();
                long failures = window.stream().filter(Sample::failed).count();
                double ratio = samples == 0 ? 0.0 : (double) failures / samples;
                long latestFailure = window.stream().filter(Sample::failed)
                        .mapToLong(Sample::timestamp).max().orElse(0L);

                QuotaState next = s.currentState();
                boolean cooldownElapsed =
                        now - s.lastTransitionMillis() >= COOLDOWN_MS;
                if (samples >= MIN_SAMPLES && cooldownElapsed) {
                    // 降级还要求最近一次失败发生在最近一个冷却窗口内，
                    // 否则陈旧失败不能在恢复迹象出现后再次触发降级。
                    boolean freshFailure = now - latestFailure <= COOLDOWN_MS;
                    if (next == QuotaState.NORMAL && ratio >= DEGRADE_RATIO
                            && freshFailure) {
                        next = QuotaState.DEGRADED;
                    } else if (next == QuotaState.DEGRADED && ratio <= RECOVER_RATIO) {
                        next = QuotaState.NORMAL;
                    }
                }
                boolean changed = next != s.currentState();
                if (changed) {
                    QuotaState switchedTo = next;
                    log.warn("降级状态切换: tenantId={}, {} -> {}, samples={}, failureRatio={}",
                            id, s.currentState(), switchedTo, samples,
                            String.format("%.2f", ratio));
                    QuotaPolicy current = quotaManager.find(id)
                            .map(QuotaAccount::getPolicy).orElse(null);
                    long version = current == null ? 1 : current.version() + 1;
                    quotaManager.find(id).ifPresent(a -> a.setPolicy(
                            new QuotaPolicy(switchedTo,
                                    current == null ? 0 : current.throttlePercent(), version)));
                    return new TenantState(window, switchedTo, now);
                }
                log.debug("降级状态评估: tenantId={}, state={}, samples={}, failureRatio={}",
                        id, next, samples, String.format("%.2f", ratio));
                return new TenantState(window, s.currentState(), s.lastTransitionMillis());
            }
        });
        return state.currentState();
    }

    /** 管理端手动切换（如 THROTTLED 限流演练），并重置冷却计时。 */
    public void manualOverride(String tenantId, QuotaState target, int throttlePercent) {
        QuotaAccount account = quotaManager.accountFor(tenantId);
        QuotaPolicy current = account.getPolicy();
        account.setPolicy(new QuotaPolicy(target, throttlePercent, current.version() + 1));
        states.compute(tenantId, (id, previous) -> new TenantState(
                previous == null ? new ArrayDeque<Sample>() : previous.window(),
                target, System.currentTimeMillis()));
        log.info("手动策略覆盖: tenantId={}, state={}, throttlePercent={}",
                tenantId, target, throttlePercent);
    }

    public QuotaState currentState(String tenantId) {
        TenantState s = states.get(tenantId);
        return s == null ? QuotaState.NORMAL : s.currentState();
    }
}

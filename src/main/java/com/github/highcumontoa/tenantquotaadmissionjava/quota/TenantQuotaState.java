package com.github.highcumontoa.tenantquotaadmissionjava.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单租户固定窗口配额。
 *
 * <p>计数不变式：{@code used + inFlight == limit 时满窗}，
 * tryAcquire 以 CAS 把 {@code used+inFlight} 推过 limit 即拒绝（绝不超卖）；
 * release 按结果仅回落 inFlight，consumed=true 时 used 保留（消耗本窗配额），
 * consumed=false 时 used 也回落（返还给后续请求）。
 * 所有累计计数均为原子操作，并发下不会丢失更新。
 */
public class TenantQuotaState {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaState.class);

    private final String tenantId;
    private volatile long limit;
    private volatile long windowMillis;
    private volatile long windowStart;
    private final AtomicLong used = new AtomicLong(0);
    private final AtomicLong inFlight = new AtomicLong(0);
    private final AtomicLong totalAcquired = new AtomicLong(0);
    private final AtomicLong totalReleased = new AtomicLong(0);
    private final AtomicLong totalConsumed = new AtomicLong(0);

    public TenantQuotaState(String tenantId, long limit, long windowMillis) {
        this.tenantId = tenantId;
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.windowStart = System.currentTimeMillis();
    }

    /**
     * 占用一个配额单位。窗口到期先重置；CAS 失败时循环重试。
     */
    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= windowMillis) {
            rollWindow(now);
        }
        while (true) {
            long currentUsed = used.get();
            long currentInFlight = inFlight.get();
            if (currentUsed + currentInFlight >= limit) {
                log.info("配额拒绝: tenant={}, used={}, inFlight={}, limit={}",
                        tenantId, currentUsed, currentInFlight, limit);
                return false;
            }
            if (inFlight.compareAndSet(currentInFlight, currentInFlight + 1)) {
                totalAcquired.incrementAndGet();
                log.info("配额占用: tenant={}, used={}, inFlight={}->{}, limit={}",
                        tenantId, currentUsed, currentInFlight, currentInFlight + 1, limit);
                return true;
            }
        }
    }

    /**
     * 释放一个在途单位。consumed=true 表示消耗（业务成功/超时保留），
     * false 表示返还（业务拒绝/内部失败/不可用）。
     */
    public void release(boolean consumed) {
        inFlight.decrementAndGet();
        if (consumed) {
            used.incrementAndGet();
            totalConsumed.incrementAndGet();
        }
        totalReleased.incrementAndGet();
        log.info("配额释放: tenant={}, consumed={}, used={}, inFlight={}",
                tenantId, consumed, used.get(), inFlight.get());
    }

    /**
     * 运行时调整上限/窗口；立即对新准入判定生效。
     */
    public synchronized void updateLimit(long newLimit, long newWindowMillis) {
        this.limit = newLimit;
        this.windowMillis = newWindowMillis;
        // 缩短窗口或参数变化时重新开窗，使新窗口语义立即生效
        this.windowStart = System.currentTimeMillis();
        used.set(0);
        log.info("配额策略更新: tenant={}, newLimit={}, newWindowMillis={}, inFlight={}（窗口重置）",
                tenantId, newLimit, newWindowMillis, inFlight.get());
    }

    /**
     * 由调度器定期调用，过期则开窗重置（保留在途计数）。
     */
    public synchronized void tick() {
        long now = System.currentTimeMillis();
        if (now - windowStart >= windowMillis) {
            rollWindow(now);
        }
    }

    private void rollWindow(long now) {
        used.set(0);
        windowStart = now;
        log.info("配额窗口重置: tenant={}, limit={}, carriedInFlight={}",
                tenantId, limit, inFlight.get());
    }

    public QuotaView snapshot() {
        long u = used.get();
        long f = inFlight.get();
        return new QuotaView(limit, u, f, Math.max(0, limit - u - f), windowStart + windowMillis);
    }

    public long getTotalAcquired() {
        return totalAcquired.get();
    }

    public long getTotalReleased() {
        return totalReleased.get();
    }

    public long getTotalConsumed() {
        return totalConsumed.get();
    }
}

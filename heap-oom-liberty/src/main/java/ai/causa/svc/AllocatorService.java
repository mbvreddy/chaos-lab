package ai.causa.svc;

import ai.causa.consts.Constants;
import ai.causa.utils.StartupTime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.*;
import java.util.logging.Logger;

@ApplicationScoped
public class AllocatorService {
    private static final Logger LOGGER = Logger.getLogger(AllocatorService.class.getName());

    @Inject
    StartupTime clock;

    @ConfigProperty(name = "crash.oom-policy", defaultValue = "request")
    Constants.OomPolicy policy;

    // Request-bound
    @ConfigProperty(name = "crash.req.total", defaultValue = "100")
    long reqTotal;

    // Time-bound
    @ConfigProperty(name = "crash.time.duration-seconds", defaultValue = "30")
    long durationSeconds;
    @ConfigProperty(name = "crash.time.target-rps", defaultValue = "10000")
    long targetRps;
    @ConfigProperty(name = "crash.time.auto-allocate-on-deadline", defaultValue = "true")
    boolean autoOnDeadline;

    // Realistic
    @ConfigProperty(name = "crash.realistic.alloc-prob", defaultValue = "0.7")
    double realisticAllocProb;
    @ConfigProperty(name = "crash.realistic.alloc-size-bytes", defaultValue = "1048576")
    long realisticAllocBytes;
    @ConfigProperty(name = "crash.realistic.dealloc-prob", defaultValue = "0.3")
    double realisticDeallocProb;
    @ConfigProperty(name = "crash.realistic.dealloc-size-bytes", defaultValue = "262144")
    long realisticDeallocBytes;

    // Common
    @ConfigProperty(name = "crash.touch-pages", defaultValue = "true")
    boolean touchPages;
    @ConfigProperty(name = "crash.max-retained-chunks", defaultValue = "2147483647")
    int maxRetainedChunks;

    private final Random rnd = new Random();

    // "Live" memory we keep to push heap toward OOM
    private final List<byte[]> retained = new ArrayList<>();
    // Small churn buffer to mimic short-lived objects
    private final Deque<byte[]> churn = new ArrayDeque<>(64);

    // Counters
    private long requestCount = 0;

    // --- Time-bound bookkeeping (virtual progress model) ---
    private final Object timeLock = new Object();
    private volatile long virtualApplied = 0; // total "virtual" requests we've already accounted for
    private volatile boolean deadlineTriggered = false;

    // Called by scheduler to auto-allocate when the deadline passes without traffic
    public synchronized void maybeAutoAllocateOnDeadline() {
        if (policy != Constants.OomPolicy.time || !autoOnDeadline)
            return;
        long elapsed = clock.uptimeMillis();
        long deadlineMillis = durationSeconds * 1000L;
        if (elapsed < deadlineMillis || deadlineTriggered)
            return;
        deadlineTriggered = true;

        // Allocate the full remaining headroom in one go → OOM
        long remaining = bytesRemainingToMax();
        if (remaining > 0) {
            allocateAndRetainBytes(remaining);
        }
    }

    public synchronized Result onRequest() {
        requestCount++;

        return switch (policy) {
            case request -> applyRequestBound();
            case time -> applyTimeBound();
            case realistic -> applyRealistic();
        };
    }

    public synchronized Status status() {
        Runtime rt = Runtime.getRuntime();
        return new Status(
                policy.name(),
                requestCount,
                retained.size(),
                usedBytes(rt),
                rt.totalMemory(),
                rt.maxMemory(),
                clock.uptimeMillis(),
                reqTotal,
                durationSeconds,
                targetRps,
                virtualApplied,
                deadlineTriggered);
    }

    private Result applyRequestBound() {
        long FINAL_OOM_MARGIN = 50L * 1024 * 1024;
        // Plan to OOM by the Nth request:
        long n = Math.max(1, reqTotal);
        long requestsLeft = Math.max(1, n - (requestCount - 1));
        long remaining = bytesRemainingInHeap();
        long bytesThisRequest = Math.max(1, divCeil(remaining, requestsLeft));
        if (requestsLeft == 1)
            bytesThisRequest = remaining + FINAL_OOM_MARGIN;

        allocateAndRetainBytes(bytesThisRequest);
        LOGGER.info(String.format("[request-mode] req=%d left=%d alloc=%dB remaining=%dB max=%dB retainedChunks=%d",
                requestCount,
                requestsLeft,
                bytesThisRequest,
                remaining,
                Runtime.getRuntime().maxMemory(),
                retained.size()));

        return buildResult("request", bytesThisRequest, remaining, requestsLeft);
    }

    private Result applyTimeBound() {
        long totalVirtual = Math.max(1, durationSeconds * targetRps); // expected total "virtual requests"
        long elapsed = clock.uptimeMillis();
        long expectedSoFar = Math.min(totalVirtual, (elapsed / 1000L) * targetRps);

        long toApplyNow;
        synchronized (timeLock) {
            // If this is the very first request *after* deadline and auto hasn't run,
            // we still "catch up" the full deficit (will OOM).
            long deficit = Math.max(0, expectedSoFar - virtualApplied);
            // Also count this request itself as one virtual unit of work
            toApplyNow = deficit + 1;
            virtualApplied += toApplyNow;
        }

        long remainingVirtual = Math.max(1, totalVirtual - (virtualApplied - toApplyNow));
        long remainingBytes = bytesRemainingToMax();
        long bytesPerVirtual = Math.max(1, divCeil(remainingBytes, remainingVirtual));
        long bytesThisRequest;
        if (toApplyNow > remainingBytes / bytesPerVirtual) {
            bytesThisRequest = remainingBytes;
        } else {
            bytesThisRequest = bytesPerVirtual * toApplyNow;
        }

        allocateAndRetainBytes(bytesThisRequest);

        // If no traffic at all and we pass the deadline, the scheduler will
        // auto-allocate.
        return buildResult("time", bytesThisRequest, remainingBytes, remainingVirtual);
    }

    private Result applyRealistic() {
        long bytesThisRequest = 0;

        // Allocate?
        if (rnd.nextDouble() < realisticAllocProb) {
            long b = realisticAllocBytes;
            allocateAndRetainBytes(b);
            bytesThisRequest += b;
        }
        // Deallocate?
        if (rnd.nextDouble() < realisticDeallocProb && !retained.isEmpty()) {
            long remaining = realisticDeallocBytes;
            // Drop as many retained chunks as needed to approximate the dealloc size
            while (remaining > 0 && !retained.isEmpty()) {
                int idx = rnd.nextInt(retained.size());
                retained.set(idx, retained.get(retained.size() - 1));
                byte[] removed = retained.remove(retained.size() - 1);
                remaining -= removed.length;
            }
        }

        long remainingBytes = bytesRemainingToMax();
        return buildResult("realistic", bytesThisRequest, remainingBytes, -1);
    }

    // --- Core allocation helpers ---

    private void allocateAndRetainBytes(long bytes) {
        // Allocate in ~1MiB-ish chunks to avoid giant arrays & allow page touching
        final int chunk = 1 << 20;
        long remaining = bytes;

        while (remaining > 0 && retained.size() < maxRetainedChunks) {
            int size = (int) Math.min(remaining, chunk);
            byte[] arr = new byte[size];

            if (touchPages) {
                for (int i = 0; i < size; i += 4096)
                    arr[i] = (byte) 1;
            }

            // Keep some arrays short-lived to mimic churn
            churn.addLast(arr);
            if (churn.size() > 64)
                churn.removeFirst();

            // Retain (live) to build pressure
            retained.add(arr);
            remaining -= size;
        }
    }

    private long bytesRemainingToMax() {
        Runtime rt = Runtime.getRuntime();
        long used = usedBytes(rt);
        long max = rt.maxMemory(); // ~ -Xmx
        long remaining = max - used;
        return Math.max(0, remaining);
    }

    private long bytesRemainingInHeap() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = bean.getHeapMemoryUsage();

        long used = heap.getUsed();
        long max = heap.getMax(); // -Xmx or -1 if undefined
        long committed = heap.getCommitted();

        long effectiveLimit;

        if (max > 0) {
            // Properly configured -Xmx
            effectiveLimit = max;
        } else {
            // No max defined → fall back to committed heap
            effectiveLimit = committed;
        }

        long remaining = effectiveLimit - used;

        return Math.max(0L, remaining);
    }

    private long usedBytes(Runtime rt) {
        // Approximate bytes used relative to max: (currently allocated - free within
        // allocated) + expandable headroom?
        // For OOM planning, use: used = total - free (current live) and drive up to
        // maxMemory
        return rt.totalMemory() - rt.freeMemory();
    }

    private long divCeil(long a, long b) {
        return (a + b - 1) / b;
    }

    private Result buildResult(String mode, long bytesThisRequest, long remainingBytes, long unitsLeft) {
        Runtime rt = Runtime.getRuntime();
        return new Result(
                mode,
                requestCount,
                bytesThisRequest,
                retained.size(),
                usedBytes(rt),
                rt.totalMemory(),
                rt.maxMemory(),
                remainingBytes,
                unitsLeft,
                clock.uptimeMillis());
    }

    // DTOs
    public record Result(
            String policy,
            long requestCount,
            long bytesAllocatedThisRequest,
            int retainedChunks,
            long heapUsedBytes,
            long heapTotalBytes,
            long heapMaxBytes,
            long bytesRemainingToMax,
            long unitsLeft, // requestsLeft or remainingVirtual; -1 for realistic
            long uptimeMillis) {
    }

    public record Status(
            String policy,
            long requestCount,
            int retainedChunks,
            long heapUsedBytes,
            long heapTotalBytes,
            long heapMaxBytes,
            long uptimeMillis,
            long reqTotal,
            long durationSeconds,
            long targetRps,
            long timeVirtualApplied,
            boolean timeDeadlineTriggered) {
    }
}

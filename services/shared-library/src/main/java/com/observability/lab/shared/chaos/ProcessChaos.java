package com.observability.lab.shared.chaos;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The faults that can only be produced from inside the process.
 *
 * <p>This is the whole reason step 17 exists as a separate thing from the Toxiproxy work. A slow
 * database, a refused connection and a black-holed dependency are all injectable from outside, and
 * are, in {@code docs/Simulation.md}. None of the faults here are: a JVM cannot be made to leak
 * memory, burn CPU, or deadlock two of its own threads by anything sitting in the network path.
 *
 * <p>Everything is bounded by {@link ChaosProperties}. The one exception is {@link #deadlock()},
 * which cannot be undone — see its own note.
 */
public class ProcessChaos {

    private static final Logger log = LoggerFactory.getLogger(ProcessChaos.class);

    /**
     * The leak. Static-lifetime by construction, which is exactly what makes it a leak rather than a
     * large allocation: the reference is held by a live object graph, so no collector will reclaim it
     * and it shows up in {@code alloc_live} rather than {@code alloc}.
     */
    private final List<byte[]> retained = new ArrayList<>();

    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicBoolean cpuBurnRunning = new AtomicBoolean();
    private final AtomicBoolean deadlockCreated = new AtomicBoolean();
    private final ChaosProperties properties;

    public ProcessChaos(ChaosProperties properties) {
        this.properties = properties;
    }

    // ------------------------------------------------------------------------------------------
    // CPU
    // ------------------------------------------------------------------------------------------

    /**
     * Burns whole cores for a bounded period.
     *
     * <p>A tight arithmetic loop rather than something that allocates, so the flame graph shows CPU
     * and nothing else. That distinction is the lesson: this fault raises {@code
     * process_cpu_usage} and leaves heap flat, whereas {@link #leakMemory} raises heap and leaves CPU
     * flat until the collector starts fighting for it.
     *
     * <p>Threads are daemons so that a JVM shutdown during the burn is not blocked by it.
     */
    public Map<String, Object> burnCpu(int threads, Duration duration) {
        int effectiveThreads = Math.clamp(threads, 1, properties.maxCpuThreads());
        Duration effective = duration.compareTo(properties.maxCpuDuration()) > 0
                ? properties.maxCpuDuration()
                : duration;

        if (!cpuBurnRunning.compareAndSet(false, true)) {
            return result("already-running", Map.of());
        }

        long deadline = System.nanoTime() + effective.toNanos();
        ExecutorService pool = Executors.newFixedThreadPool(effectiveThreads, r -> {
            Thread t = new Thread(r, "chaos-cpu-burn");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < effectiveThreads; i++) {
            pool.submit(() -> {
                // A result that is used, so the JIT cannot delete the loop as dead code - which it
                // very much will if the value is discarded, producing a "CPU spike" that spikes
                // nothing and a bug report about the lab being broken.
                double sink = 0;
                while (System.nanoTime() < deadline) {
                    sink += Math.sqrt(ThreadLocalRandom.current().nextDouble() + 1.0d);
                }
                if (Double.isNaN(sink)) {
                    log.trace("unreachable, and only here so the loop above cannot be optimised away");
                }
            });
        }
        pool.shutdown();
        Thread watcher = new Thread(() -> {
            try {
                pool.awaitTermination(effective.toSeconds() + 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                cpuBurnRunning.set(false);
                log.warn("Chaos CPU burn finished: threads={}", effectiveThreads);
            }
        }, "chaos-cpu-watcher");
        watcher.setDaemon(true);
        watcher.start();

        log.warn("Chaos CPU burn started: threads={} durationSeconds={}", effectiveThreads, effective.toSeconds());
        return result("started", Map.of(
                "threads", effectiveThreads,
                "durationSeconds", effective.toSeconds(),
                "availableProcessors", Runtime.getRuntime().availableProcessors()));
    }

    // ------------------------------------------------------------------------------------------
    // Memory
    // ------------------------------------------------------------------------------------------

    /**
     * Retains memory that will never be released until {@link #releaseMemory()} is called.
     *
     * <p>The arrays are filled rather than left zeroed. An untouched allocation can be backed by
     * pages the OS never commits, so the JVM heap grows while the container's RSS does not — and the
     * gap between {@code jvm_memory_used_bytes} and container memory is one of the things this
     * scenario is supposed to teach. Filling them makes both numbers move together, which is what a
     * real leak of live objects does.
     */
    public Map<String, Object> leakMemory(int megabytes) {
        int requested = Math.max(1, megabytes);
        long currentMb = retainedBytes.get() / (1024 * 1024);
        long headroom = properties.maxMemoryLeakMb() - currentMb;
        if (headroom <= 0) {
            return result("ceiling-reached", Map.of(
                    "retainedMb", currentMb,
                    "ceilingMb", properties.maxMemoryLeakMb()));
        }
        int effective = (int) Math.min(requested, headroom);

        synchronized (retained) {
            for (int i = 0; i < effective; i++) {
                byte[] chunk = new byte[1024 * 1024];
                // Touch every page so the allocation is real rather than virtual.
                for (int b = 0; b < chunk.length; b += 4096) {
                    chunk[b] = 1;
                }
                retained.add(chunk);
            }
        }
        long total = retainedBytes.addAndGet((long) effective * 1024 * 1024);
        log.warn("Chaos memory retained: addedMb={} totalMb={}", effective, total / (1024 * 1024));
        return result("retained", Map.of(
                "addedMb", effective,
                "totalRetainedMb", total / (1024 * 1024),
                "ceilingMb", properties.maxMemoryLeakMb(),
                "clamped", effective < requested));
    }

    /** Drops the retained references. The heap does not shrink until a collection runs. */
    public Map<String, Object> releaseMemory() {
        long freed;
        synchronized (retained) {
            freed = retainedBytes.getAndSet(0);
            retained.clear();
        }
        log.warn("Chaos memory released: freedMb={}", freed / (1024 * 1024));
        return result("released", Map.of(
                "freedMb", freed / (1024 * 1024),
                // Deliberately no System.gc() call. Watching the heap stay high until the collector
                // decides to run is the more useful observation: "released" and "reclaimed" are
                // different events, and conflating them is how people misread a leak graph.
                "note", "heap stays high until the next collection; that delay is the point"));
    }

    public long retainedMegabytes() {
        return retainedBytes.get() / (1024 * 1024);
    }

    // ------------------------------------------------------------------------------------------
    // Deadlock
    // ------------------------------------------------------------------------------------------

    /**
     * Deadlocks two threads against each other, permanently.
     *
     * <p><strong>This one cannot be undone.</strong> The two threads are unrecoverable for the life
     * of the JVM; the only reset is a container restart. That is not a limitation to work around, it
     * is the fault being modelled — a real deadlock is exactly this, and "restart the process" is
     * exactly the real remedy.
     *
     * <p>Two locks taken in opposite orders, which is the textbook shape and the one
     * {@link ThreadMXBean#findDeadlockedThreads()} detects. That detection is the point of the
     * scenario: the JVM knows, and will tell anything that asks — a thread dump, an actuator endpoint,
     * or Pyroscope's lock profile. Nothing in metrics or traces will say "deadlock"; the two requests
     * simply never finish.
     */
    public Map<String, Object> deadlock() {
        if (!deadlockCreated.compareAndSet(false, true)) {
            return result("already-deadlocked", Map.of("deadlockedThreads", detectDeadlockedThreads()));
        }
        Object lockA = new Object();
        Object lockB = new Object();
        CountDownLatch bothStarted = new CountDownLatch(2);

        Thread first = new Thread(() -> holdThenGrab(lockA, lockB, bothStarted), "chaos-deadlock-1");
        Thread second = new Thread(() -> holdThenGrab(lockB, lockA, bothStarted), "chaos-deadlock-2");
        first.setDaemon(true);
        second.setDaemon(true);
        first.start();
        second.start();

        try {
            bothStarted.await(5, TimeUnit.SECONDS);
            // Long enough for both threads to be blocked on the second monitor before we look.
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long[] deadlocked = detectDeadlockedThreads();
        log.error("Chaos deadlock created: threads={} - this is PERMANENT until the process restarts",
                deadlocked.length);
        return result("deadlocked", Map.of(
                "deadlockedThreadCount", deadlocked.length,
                "recoverable", false,
                "remedy", "restart the container: docker restart <service>"));
    }

    private static void holdThenGrab(Object hold, Object grab, CountDownLatch started) {
        synchronized (hold) {
            started.countDown();
            try {
                // Both threads must be holding their first lock before either reaches for the
                // second, or one simply completes and there is no deadlock at all.
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (grab) {
                log.error("unreachable: the deadlock did not form");
            }
        }
    }

    /** What {@code jstack} would report, exposed so a scenario can assert on it. */
    public long[] detectDeadlockedThreads() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] found = threads.findDeadlockedThreads();
        return found == null ? new long[0] : found;
    }

    // ------------------------------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------------------------------

    /**
     * Floods the log pipeline.
     *
     * <p>The target is not the service, it is everything downstream of it: the async appender's ring
     * buffer, the file, the shipping agents tailing it, Loki's ingestion limits. A burst large enough
     * will make the appender drop lines, and finding out which component drops first — and whether
     * anything says so — is the exercise.
     *
     * <p>Each line carries a distinct sequence number so gaps in Loki are countable rather than
     * merely suspected.
     */
    public Map<String, Object> burstLogs(int lines, String level, int paddingBytes) {
        int effective = Math.clamp(lines, 1, properties.maxLogBurstLines());
        String padding = "x".repeat(Math.clamp(paddingBytes, 0, 4096));
        String burstId = Long.toHexString(System.nanoTime());
        long start = System.nanoTime();

        for (int i = 0; i < effective; i++) {
            String message = "chaos log burst id={} seq={}/{} padding={}";
            switch (level == null ? "INFO" : level.toUpperCase(java.util.Locale.ROOT)) {
                case "ERROR" -> log.error(message, burstId, i, effective, padding);
                case "WARN" -> log.warn(message, burstId, i, effective, padding);
                case "DEBUG" -> log.debug(message, burstId, i, effective, padding);
                default -> log.info(message, burstId, i, effective, padding);
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return result("burst-emitted", Map.of(
                "burstId", burstId,
                "lines", effective,
                "level", level == null ? "INFO" : level,
                "elapsedMs", elapsedMs,
                "verify", "count_over_time({service=\"...\"} |= \"" + burstId + "\" [5m]) should equal "
                        + effective));
    }

    // ------------------------------------------------------------------------------------------
    // Internal load
    // ------------------------------------------------------------------------------------------

    /**
     * Saturates the process from the inside, with no network involved.
     *
     * <p>Different from a k6 run, and deliberately so. k6 offers load at the front door, where the
     * gateway's rate limiter, Tomcat's accept queue and the connection pool all get a say. This
     * submits work directly to a pool of threads inside the JVM, which saturates whatever the task
     * touches without any of that in the way — so the bottleneck it finds is the one in the
     * application rather than the one at the edge.
     *
     * <p>Runs asynchronously: the caller gets an immediate acknowledgement, because a request that
     * blocked until the burst finished would be occupying one of the very threads under test.
     */
    public Map<String, Object> internalTraffic(int requests, int concurrency, Runnable unitOfWork) {
        int effectiveRequests = Math.clamp(requests, 1, properties.maxTrafficRequests());
        int effectiveConcurrency = Math.clamp(concurrency, 1, 512);

        ExecutorService pool = Executors.newFixedThreadPool(effectiveConcurrency, r -> {
            Thread t = new Thread(r, "chaos-traffic");
            t.setDaemon(true);
            return t;
        });
        AtomicLong failures = new AtomicLong();
        for (int i = 0; i < effectiveRequests; i++) {
            pool.submit(() -> {
                try {
                    unitOfWork.run();
                } catch (RuntimeException e) {
                    // Expected in bulk once the pool saturates: that is the observation, not an error
                    // in the harness. Counted rather than logged per occurrence, because logging
                    // thousands of stack traces would itself become the load being measured.
                    failures.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        log.warn("Chaos internal traffic submitted: requests={} concurrency={}",
                effectiveRequests, effectiveConcurrency);
        return result("submitted", Map.of(
                "requests", effectiveRequests,
                "concurrency", effectiveConcurrency,
                "note", "runs asynchronously; watch hikaricp_connections_pending and tomcat_threads_busy"));
    }

    private static Map<String, Object> result(String outcome, Map<String, Object> detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", outcome);
        body.putAll(detail);
        return body;
    }
}

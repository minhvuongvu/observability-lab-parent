package com.observability.lab.shared.chaos;

import com.observability.lab.shared.api.ApiResponse;
import com.observability.lab.shared.exception.BusinessException;
import com.observability.lab.shared.exception.PlatformErrorCode;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberate faults, for observability learning only.
 *
 * <p>This controller exists so that every signal the platform collects can be seen responding to a
 * known cause. An alert that has never fired is untested; a dashboard nobody has watched break is
 * decoration. These endpoints are how that gets fixed.
 *
 * <h2>Where the boundary is</h2>
 *
 * <p>Everything here is a fault the process must inflict on <em>itself</em>. Faults in the network
 * path — a slow database, an unreachable broker, a black-holed object store — are injected from
 * outside by Toxiproxy and are documented in {@code docs/Simulation.md}. That split is not
 * arbitrary: a fault injected from outside needs no application code and cannot be left switched on
 * in production, so anything that can live out there should.
 *
 * <h2>Guards</h2>
 *
 * <ul>
 *   <li>The whole controller is registered only under the {@code local} and {@code dev} profiles, so
 *       under {@code prod} these paths do not exist and return 404 — not 403, which would confirm
 *       they are there.
 *   <li>Every path additionally requires the {@code ADMIN} role, enforced in the shared resource
 *       server configuration.
 *   <li>Every toggle expires on its own. "Forgot to reset" is a lab that recovers by itself.
 * </ul>
 *
 * <p>The belt-and-braces is on purpose. A chaos endpoint reachable in production is not a learning
 * tool; it is a remote denial-of-service with documentation.
 */
@RestController
@RequestMapping("/api/v1/chaos")
public class ChaosController {

    private final ChaosRegistry registry;
    private final ProcessChaos process;
    private final ChaosProperties properties;
    private final ObjectProvider<DatabaseChaos> databaseChaos;
    private final ObjectProvider<MessagingChaos> messagingChaos;
    private final ObjectProvider<ResilienceChaos> resilienceChaos;

    public ChaosController(
            ChaosRegistry registry,
            ProcessChaos process,
            ChaosProperties properties,
            ObjectProvider<DatabaseChaos> databaseChaos,
            ObjectProvider<MessagingChaos> messagingChaos,
            ObjectProvider<ResilienceChaos> resilienceChaos) {
        this.registry = registry;
        this.process = process;
        this.properties = properties;
        this.databaseChaos = databaseChaos;
        this.messagingChaos = messagingChaos;
        this.resilienceChaos = resilienceChaos;
    }

    // ------------------------------------------------------------------------------------------
    // Inspection and reset
    // ------------------------------------------------------------------------------------------

    /**
     * What is currently broken on purpose.
     *
     * <p>The first thing to call when a number looks wrong. It is also what {@code scripts/chaos.sh}
     * prints before every load run, because a run against a stack that still carries a fault from an
     * earlier experiment produces results that look like a regression and are an artefact.
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> active() {
        Instant now = Instant.now();
        List<Map<String, Object>> faults = new ArrayList<>();
        for (ChaosToggle toggle : registry.active()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", toggle.name());
            entry.put("ratio", toggle.ratio());
            entry.put("injectedAt", toggle.injectedAt().toString());
            entry.put("expiresInSeconds", toggle.remainingSeconds(now));
            entry.put("attributes", toggle.attributes());
            faults.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activeFaults", faults);
        body.put("count", faults.size());
        body.put("retainedMemoryMb", process.retainedMegabytes());
        body.put("deadlockedThreads", process.detectDeadlockedThreads().length);
        body.put("supported", supported());
        return ApiResponse.success(body);
    }

    /**
     * Switches everything off.
     *
     * <p>Everything that <em>can</em> be switched off. Retained memory is released and every toggle
     * is cleared, but a deadlock is permanent and the response says so rather than quietly reporting
     * success — see {@link ProcessChaos#deadlock()}.
     */
    @DeleteMapping
    public ApiResponse<Map<String, Object>> reset() {
        int cleared = registry.clearAll();
        Map<String, Object> released = process.releaseMemory();
        int deadlocked = process.detectDeadlockedThreads().length;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("togglesCleared", cleared);
        body.put("memoryReleasedMb", released.get("freedMb"));
        body.put("clean", deadlocked == 0);
        if (deadlocked > 0) {
            body.put("deadlockedThreads", deadlocked);
            body.put("warning", "deadlocked threads cannot be recovered; restart the container");
        }
        return ApiResponse.success(body);
    }

    @DeleteMapping("/{name}")
    public ApiResponse<Map<String, Object>> clear(@PathVariable String name) {
        return ApiResponse.success(Map.of("name", name, "wasActive", registry.clear(name)));
    }

    // ------------------------------------------------------------------------------------------
    // Request-scoped faults
    // ------------------------------------------------------------------------------------------

    /**
     * Slow request: adds latency to every API call this service serves.
     *
     * <p>Every call, not just this endpoint, and that is the point — a slow service is not a slow
     * handler. {@code /actuator} and this controller are exempt so that health checks stay green and
     * the fault remains switchable; see {@link ChaosRequestFilter}.
     */
    @PostMapping("/latency")
    public ApiResponse<Map<String, Object>> latency(@Valid @RequestBody ChaosRequests.Latency request) {
        long clamped = Math.min(request.delayMs(), properties.maxLatency().toMillis());
        ChaosToggle toggle = registry.inject(
                ChaosRequestFilter.LATENCY,
                request.ratio(),
                Duration.ofSeconds(request.ttlSeconds()),
                Map.of("delayMs", clamped, "clamped", clamped != request.delayMs()));
        return ApiResponse.success(describe(toggle,
                "every API request now sleeps " + clamped + "ms on its request thread"));
    }

    /** Exception simulation: a proportion of API calls fail before reaching their handler. */
    @PostMapping("/exception")
    public ApiResponse<Map<String, Object>> exception(@Valid @RequestBody ChaosRequests.Exception request) {
        String message = request.message() == null || request.message().isBlank()
                ? "chaos: deliberate failure injected for observability learning"
                : request.message();
        ChaosToggle toggle = registry.inject(
                ChaosRequestFilter.EXCEPTION,
                request.ratio(),
                Duration.ofSeconds(request.ttlSeconds()),
                Map.of("message", message));
        return ApiResponse.success(describe(toggle,
                "roughly " + Math.round(request.ratio() * 100) + "% of API calls now return 500"));
    }

    // ------------------------------------------------------------------------------------------
    // Process faults
    // ------------------------------------------------------------------------------------------

    /** CPU spike: burns whole cores for a bounded period. */
    @PostMapping("/cpu")
    public ApiResponse<Map<String, Object>> cpu(@Valid @RequestBody ChaosRequests.Cpu request) {
        return ApiResponse.success(
                process.burnCpu(request.threads(), Duration.ofSeconds(request.durationSeconds())));
    }

    /** Memory leak: retains memory that only an explicit release will free. */
    @PostMapping("/memory-leak")
    public ApiResponse<Map<String, Object>> memoryLeak(@Valid @RequestBody ChaosRequests.MemoryLeak request) {
        return ApiResponse.success(process.leakMemory(request.megabytes()));
    }

    @DeleteMapping("/memory-leak")
    public ApiResponse<Map<String, Object>> releaseMemory() {
        return ApiResponse.success(process.releaseMemory());
    }

    /**
     * Deadlock: two threads, two locks, opposite order. Permanent.
     *
     * <p>Returns 200 with {@code recoverable: false} rather than pretending this is undoable.
     */
    @PostMapping("/deadlock")
    public ApiResponse<Map<String, Object>> deadlock() {
        return ApiResponse.success(process.deadlock());
    }

    /** Bulk logging: floods the log pipeline with a countable burst. */
    @PostMapping("/log-burst")
    public ApiResponse<Map<String, Object>> logBurst(@Valid @RequestBody ChaosRequests.LogBurst request) {
        return ApiResponse.success(
                process.burstLogs(request.lines(), request.level(), request.paddingBytes()));
    }

    /**
     * Large payload: returns a response of the requested size.
     *
     * <p>Streamed as plain text rather than wrapped in the API envelope, because the point is the
     * bytes on the wire: Kong's {@code request-size-limiting} plugin, Nginx's buffers, the client's
     * memory and the trace's payload attributes all have opinions about a 50 MB response, and the
     * envelope would only get in the way of seeing them.
     */
    @GetMapping(value = "/large-payload", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> largePayload(@RequestParam(defaultValue = "1024") int sizeKb) {
        int effective = Math.clamp(sizeKb, 1, properties.maxPayloadKb());
        // A repeated pattern rather than random bytes: it compresses, so the difference between the
        // response size and the bytes actually transferred is visible in the gateway's logs. That gap
        // is usually the first surprise in a large-payload investigation.
        String block = "chaos-payload-".repeat(64);
        StringBuilder body = new StringBuilder(effective * 1024 + block.length());
        while (body.length() < effective * 1024) {
            body.append(block);
        }
        return ResponseEntity.ok()
                .header("X-Chaos-Payload-Kb", String.valueOf(effective))
                .body(body.substring(0, effective * 1024));
    }

    /**
     * High traffic: saturates this process from the inside, with no network in the way.
     *
     * <p>Complements a k6 run rather than duplicating it. k6 offers load at the front door, where the
     * rate limiter and the accept queue get a say; this submits work straight onto threads inside the
     * JVM, so the bottleneck it finds is the application's rather than the edge's.
     */
    @PostMapping("/traffic")
    public ApiResponse<Map<String, Object>> traffic(@Valid @RequestBody ChaosRequests.Traffic request) {
        DatabaseChaos database = databaseChaos.getIfAvailable();
        Duration work = Duration.ofMillis(request.workMillis() > 0 ? request.workMillis() : 250);

        // Each unit of work takes a real connection when the service has a database, because pool
        // contention is the interesting saturation. Without one it burns CPU instead, which at least
        // saturates the thread pool.
        Runnable unit = database != null
                ? () -> database.sleepInDatabase(work)
                : () -> {
                    long until = System.nanoTime() + work.toNanos();
                    double sink = 0;
                    while (System.nanoTime() < until) {
                        sink += Math.sqrt(sink + 2.0d);
                    }
                };

        Map<String, Object> result = new LinkedHashMap<>(
                process.internalTraffic(request.requests(), request.concurrency(), unit));
        result.put("unitOfWork", database != null
                ? "a " + work.toMillis() + "ms query holding a " + database.databaseName() + " connection"
                : "a " + work.toMillis() + "ms CPU burn (no DatabaseChaos bean in this service)");
        return ApiResponse.success(result);
    }

    // ------------------------------------------------------------------------------------------
    // Dependency faults
    // ------------------------------------------------------------------------------------------

    /**
     * Database timeout: holds connections in sleeping queries.
     *
     * <p>With {@code connections} at or above the pool size this exhausts the pool, and every other
     * request in the service starts failing on connection acquisition rather than on anything to do
     * with its own work. That indirection is the lesson: the endpoint that breaks is rarely the
     * endpoint at fault.
     */
    @PostMapping("/db-slow")
    public ApiResponse<Map<String, Object>> databaseSlow(@Valid @RequestBody ChaosRequests.DatabaseSlow request) {
        DatabaseChaos database = require(databaseChaos.getIfAvailable(), "db-slow");
        Duration duration = Duration.ofSeconds(request.seconds());

        // Fire and forget: holding the request thread for the whole sleep would mean the caller could
        // not observe the pool draining while it happens.
        ExecutorService pool = Executors.newFixedThreadPool(request.connections(), r -> {
            Thread t = new Thread(r, "chaos-db-slow");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < request.connections(); i++) {
            pool.submit(() -> database.sleepInDatabase(duration));
        }
        pool.shutdown();

        return ApiResponse.success(Map.of(
                "outcome", "submitted",
                "database", database.databaseName(),
                "connectionsHeld", request.connections(),
                "seconds", request.seconds(),
                "watch", "hikaricp_connections_pending, then acquisition timeouts on unrelated endpoints"));
    }

    /**
     * Redis failure: makes the cache miss or throw while Redis itself stays healthy.
     *
     * <p>The quiet version of a cache outage. {@code chaos.sh down redis} breaks the connection and
     * readiness goes down; this leaves every health signal green while the origin takes the full
     * uncached load. See {@link ChaosCacheManager}.
     */
    @PostMapping("/cache")
    public ApiResponse<Map<String, Object>> cache(@Valid @RequestBody ChaosRequests.Cache request) {
        ChaosToggle toggle = registry.inject(
                ChaosCacheManager.FAULT,
                request.ratio(),
                Duration.ofSeconds(request.ttlSeconds()),
                Map.of("mode", request.mode()));
        String effect = ChaosCacheManager.MODE_FAIL.equals(request.mode())
                ? "cache reads and writes now throw"
                : "cache reads now always miss; every request falls through to the origin";
        return ApiResponse.success(describe(toggle, effect));
    }

    /**
     * Kafka failure: a send the broker refuses, with the cluster perfectly healthy.
     *
     * <p>Distinct from {@code chaos.sh down kafka}, which severs the connection. This one fails
     * inside the producer, immediately, with no reconnection and no network retry.
     */
    @PostMapping("/kafka")
    public ApiResponse<Map<String, Object>> kafka() {
        MessagingChaos messaging = require(messagingChaos.getIfAvailable(), "kafka");
        return ApiResponse.success(Map.of(
                "outcome", "attempted",
                "result", messaging.publishOversizedMessage(),
                "watch", "kafka_producer_record_error_total and an ERROR on this service"));
    }

    /**
     * Dead letter queue: publishes a message the consumer cannot process.
     *
     * <p>The consumer should retry it on its configured backoff, exhaust the attempts, and publish it
     * to the dead-letter topic with headers naming the original topic, partition, offset and
     * exception. Nothing consumes it from there, deliberately.
     */
    @PostMapping("/dead-letter")
    public ApiResponse<Map<String, Object>> deadLetter() {
        MessagingChaos messaging = require(messagingChaos.getIfAvailable(), "dead-letter");
        String key = messaging.publishPoisonMessage();
        return ApiResponse.success(Map.of(
                "outcome", "published",
                "key", key,
                "topic", messaging.poisonTopic(),
                "expectedDeadLetterTopic", messaging.deadLetterTopic(),
                "watch", "retries with backoff on the consumer, then one record on the dead-letter topic"));
    }

    /**
     * Retry and circuit breaker: drives real calls through the guarded client.
     *
     * <p>Meaningful only against a dependency that is actually failing, which is the design. Break it
     * first — {@code ./scripts/chaos.sh down inventory-grpc} — then call this and watch the breaker
     * move CLOSED to OPEN. Against a healthy dependency it reports that nothing tripped, which is
     * also a correct answer.
     */
    @PostMapping("/circuit-breaker")
    public ApiResponse<Map<String, Object>> circuitBreaker(
            @Valid @RequestBody ChaosRequests.CircuitBreaker request) {
        ResilienceChaos resilience = require(resilienceChaos.getIfAvailable(), "circuit-breaker");
        return ApiResponse.success(resilience.driveCalls(request.calls()));
    }

    @GetMapping("/circuit-breaker")
    public ApiResponse<Map<String, Object>> circuitBreakerState() {
        ResilienceChaos resilience = require(resilienceChaos.getIfAvailable(), "circuit-breaker");
        return ApiResponse.success(resilience.circuitBreakerState());
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Which faults this particular service can inject.
     *
     * <p>The two services differ — only the Order Service has a circuit breaker to trip — and a
     * scenario runner needs to know that without a 404 telling it the hard way.
     */
    private List<String> supported() {
        List<String> names = new ArrayList<>(List.of(
                "latency", "exception", "cpu", "memory-leak", "deadlock",
                "log-burst", "large-payload", "traffic", "cache"));
        if (databaseChaos.getIfAvailable() != null) {
            names.add("db-slow");
        }
        if (messagingChaos.getIfAvailable() != null) {
            names.add("kafka");
            names.add("dead-letter");
        }
        if (resilienceChaos.getIfAvailable() != null) {
            names.add("circuit-breaker");
        }
        return names;
    }

    private static <T> T require(T bean, String fault) {
        if (bean == null) {
            throw new BusinessException(PlatformErrorCode.METHOD_NOT_SUPPORTED,
                    "This service does not implement the '" + fault + "' fault. "
                            + "GET /api/v1/chaos lists what it does support.");
        }
        return bean;
    }

    private Map<String, Object> describe(ChaosToggle toggle, String effect) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", "injected");
        body.put("name", toggle.name());
        body.put("ratio", toggle.ratio());
        body.put("expiresInSeconds", toggle.remainingSeconds(Instant.now()));
        body.put("attributes", toggle.attributes());
        body.put("effect", effect);
        body.put("clear", "DELETE /api/v1/chaos/" + toggle.name());
        return body;
    }
}

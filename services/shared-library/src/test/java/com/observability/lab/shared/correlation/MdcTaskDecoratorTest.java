package com.observability.lab.shared.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("MdcTaskDecorator")
class MdcTaskDecoratorTest {

    private final MdcTaskDecorator decorator = new MdcTaskDecorator();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("carries the submitting thread's correlation onto the worker")
    void propagatesToWorker() throws Exception {
        CorrelationContext.requestId("req-1");
        CorrelationContext.traceId("trace-abc");

        AtomicReference<String> seenRequestId = new AtomicReference<>();
        AtomicReference<String> seenTraceId = new AtomicReference<>();

        Runnable decorated = decorator.decorate(() -> {
            seenRequestId.set(CorrelationContext.requestId());
            seenTraceId.set(CorrelationContext.traceId());
        });

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(seenRequestId.get()).isEqualTo("req-1");
        assertThat(seenTraceId.get()).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("hands the worker back the context it already had")
    void restoresWorkerContext() throws Exception {
        CorrelationContext.requestId("submitting-thread");
        Runnable decorated = decorator.decorate(() -> {});

        AtomicReference<String> afterTask = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            // A pooled thread outlives the task and may already be carrying its own context.
            CorrelationContext.requestId("worker-original");
            decorated.run();
            afterTask.set(CorrelationContext.requestId());
        });
        worker.start();
        worker.join();

        // Leaking "submitting-thread" here would attribute the pool's next task to the wrong request.
        assertThat(afterTask.get()).isEqualTo("worker-original");
    }

    @Test
    @DisplayName("leaves the submitting thread untouched")
    void submittingThreadUnaffected() throws Exception {
        CorrelationContext.requestId("req-1");

        Thread worker = new Thread(decorator.decorate(() -> CorrelationContext.requestId("mutated")));
        worker.start();
        worker.join();

        assertThat(CorrelationContext.requestId()).isEqualTo("req-1");
    }
}

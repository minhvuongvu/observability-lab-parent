package com.observability.lab.shared.correlation;

import java.util.Map;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the correlation fields across a thread hand-off.
 *
 * <p>The MDC is thread-local, so work pushed onto an executor loses it and its log lines appear
 * without a trace id — orphaned exactly when something went wrong asynchronously and the context
 * matters most. Registering this decorator on an executor restores the submitting thread's fields
 * on the worker.
 *
 * <pre>{@code
 * @Bean
 * ThreadPoolTaskExecutor applicationTaskExecutor() {
 *     var executor = new ThreadPoolTaskExecutor();
 *     executor.setTaskDecorator(new MdcTaskDecorator());
 *     return executor;
 * }
 * }</pre>
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Captured on the submitting thread, at submission time.
        Map<String, String> submitted = CorrelationContext.snapshot();

        return () -> {
            // Whatever the pooled worker was carrying, so it is handed back unchanged.
            // A pool thread outlives the task, and leaking one task's identity into the next is
            // the precise failure this class exists to prevent.
            Map<String, String> workerPrevious = CorrelationContext.snapshot();
            CorrelationContext.restore(submitted);
            try {
                runnable.run();
            } finally {
                CorrelationContext.restore(workerPrevious);
            }
        };
    }
}

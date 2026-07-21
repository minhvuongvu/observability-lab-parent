package com.observability.lab.order.config;

import com.observability.lab.order.infrastructure.messaging.OutboxRelay;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the background schedule the {@link OutboxRelay} runs on.
 *
 * <p>Separate from the relay itself so that switching scheduling off — in a test, or in an instance
 * deliberately configured not to relay — is a matter of excluding one configuration class rather than
 * editing the component that does the work.
 *
 * <p>The default scheduler is single-threaded, which is the right size here: the relay is the only
 * scheduled task, and giving it a pool would let two runs of it overlap.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {
}

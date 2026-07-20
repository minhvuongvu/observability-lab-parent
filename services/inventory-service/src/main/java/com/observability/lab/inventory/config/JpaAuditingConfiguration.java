package com.observability.lab.inventory.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Switches on automatic population of the auditing columns.
 *
 * <p>Enabled by the service rather than by the shared library: turning it on attaches an entity
 * listener to every entity in the application, which a library has no business doing to a service
 * that never asked for it.
 *
 * <p>The actor comes from the {@code AuditorAware} the shared library contributes, which reads the
 * authenticated subject from the correlation context. For stock changed by the Kafka consumer there
 * is no authenticated user, so the column records {@code system} — which is a different fact from a
 * null, and worth being able to tell apart.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
public class JpaAuditingConfiguration {}

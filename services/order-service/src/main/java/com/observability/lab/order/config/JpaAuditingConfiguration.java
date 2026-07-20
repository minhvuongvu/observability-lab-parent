package com.observability.lab.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Switches on automatic population of the auditing columns.
 *
 * <p>Enabled by the service rather than by the shared library on purpose: turning it on attaches an
 * entity listener to every entity in the application, and a library has no business doing that to a
 * service that never asked for auditing and has no columns to hold it.
 *
 * <p>The actor is supplied by the {@code AuditorAware} the shared library contributes, which reads
 * the authenticated subject from the correlation context. That keeps {@code created_by} consistent
 * with the {@code user_id} on the log lines for the same request.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
public class JpaAuditingConfiguration {}

/**
 * Root package of the shared platform library.
 *
 * <p>Everything in this module is consumed by every service, so it must stay generic, stable and
 * free of business rules. The library depends on no service; services depend on it. That direction
 * is deliberate and must not be inverted.
 *
 * <h2>Packages</h2>
 *
 * <ul>
 *   <li>{@code api} &mdash; the uniform response envelope, error payload and page wrapper returned
 *       by every endpoint
 *   <li>{@code exception} &mdash; the error code model, base exception hierarchy and the global
 *       handler that maps both onto HTTP status codes and log levels
 *   <li>{@code correlation} &mdash; service identity, correlation and request ids, the inbound
 *       filter that establishes them and the MDC plumbing that propagates them
 *   <li>{@code tracing} &mdash; W3C trace context parsing and access to the active trace
 *   <li>{@code logging} &mdash; scoped structured log fields
 *   <li>{@code persistence} &mdash; base and auditing entities, and the auditor that names the actor
 *   <li>{@code util} &mdash; programmatic validation and masking of sensitive values
 *   <li>{@code autoconfigure} &mdash; Spring Boot auto-configuration, so a consuming service gets
 *       the cross-cutting behaviour by depending on this module rather than by wiring it
 * </ul>
 *
 * <h2>Dependency policy</h2>
 *
 * <p>Every Spring dependency other than {@code spring-boot-starter} is declared
 * {@code <optional>true</optional>}. A library that forces JPA onto a service which only speaks HTTP
 * is a library that dictates architecture. Each service declares the starters it needs, and the
 * auto-configuration here activates only for the ones it finds.
 *
 * @see <a href="../../../../../../../../docs/SystemDesign.md">docs/SystemDesign.md</a>
 */
package com.observability.lab.shared;

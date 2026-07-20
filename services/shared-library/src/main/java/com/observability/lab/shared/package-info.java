/**
 * Root package of the shared platform library.
 *
 * <p>Everything in this module is consumed by every service, so it must stay generic, stable and
 * free of business rules. The library depends on no service; services depend on it. That direction
 * is deliberate and must not be inverted.
 *
 * <p>Sub-packages are introduced as the platform capabilities land:
 *
 * <ul>
 *   <li>{@code api} &mdash; the uniform response envelope and error payload shared by every endpoint
 *   <li>{@code exception} &mdash; the base exception hierarchy and the global exception handler
 *   <li>{@code correlation} &mdash; correlation id, request id and MDC propagation
 *   <li>{@code tracing} &mdash; helpers for reading and enriching the active span
 *   <li>{@code persistence} &mdash; base and auditing entities
 *   <li>{@code util} &mdash; validation and general purpose helpers
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/SystemDesign.md">docs/SystemDesign.md</a>
 */
package com.observability.lab.shared;

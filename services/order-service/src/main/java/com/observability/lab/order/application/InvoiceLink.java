package com.observability.lab.order.application;

import java.net.URI;
import java.time.Instant;

/**
 * A time-limited way to fetch one invoice.
 *
 * <p>{@code expiresAt} is returned alongside the URL rather than left for the caller to infer. A
 * client that caches the link needs to know when it stops working, and the alternative — discovering
 * it through a 403 from object storage — is a much worse way to find out.
 *
 * @param url       signed, single-object, read-only
 * @param expiresAt when the signature stops being accepted
 */
public record InvoiceLink(URI url, Instant expiresAt) {}

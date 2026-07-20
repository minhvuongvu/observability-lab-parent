package com.observability.lab.order.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * Enables declarative HTTP clients.
 *
 * <p>Scanning is restricted to the client package rather than the whole application. A broad scan
 * would search every package on every startup, and more importantly it makes it possible for a
 * {@code @FeignClient} to be declared anywhere — outbound calls belong in one place where they can
 * be found and reviewed.
 *
 * <p>Kept off {@code OrderServiceApplication} so the entry point stays a bootstrap and nothing else.
 */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(basePackages = "com.observability.lab.order.infrastructure.client")
public class FeignClientsConfiguration {}

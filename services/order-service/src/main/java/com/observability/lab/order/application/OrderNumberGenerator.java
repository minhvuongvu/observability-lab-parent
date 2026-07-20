package com.observability.lab.order.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Produces the externally visible order number.
 *
 * <p>Format {@code ORD-yyyyMMdd-XXXXXXXX}, for example {@code ORD-20260720-7F3A9C1E}.
 *
 * <p>Two properties matter. The date prefix makes an order number self-describing in a support
 * conversation and groups a day's orders together in an index. The random suffix is
 * {@link SecureRandom}, not a counter: a sequential order number publishes how many orders the
 * business takes, and lets anyone holding one enumerate their neighbours.
 */
@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String PREFIX = "ORD-";

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public OrderNumberGenerator() {
        this(Clock.systemUTC());
    }

    /** Visible for tests, which need the date part to be predictable. */
    OrderNumberGenerator(Clock clock) {
        this.clock = clock;
    }

    /** @return a new order number, 21 characters, well inside the 40-character column */
    public String next() {
        String datePart = LocalDate.now(clock).format(DATE_PART);
        // 32 bits rendered as 8 upper-case hex digits.
        String randomPart = String.format("%08X", random.nextInt());
        return PREFIX + datePart + "-" + randomPart;
    }
}

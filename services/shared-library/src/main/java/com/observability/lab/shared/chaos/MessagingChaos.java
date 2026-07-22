package com.observability.lab.shared.chaos;

/**
 * The Kafka faults, supplied per service because each owns different topics.
 *
 * <p>Like {@link DatabaseChaos}, this is the complement to what Toxiproxy already does. A broker
 * that is unreachable is a network fault and is injected from outside. What cannot be injected from
 * outside is a message the broker accepts perfectly and the <em>consumer</em> cannot process — which
 * is the failure that actually exercises the retry policy, the backoff, and the dead-letter topic.
 *
 * <p>That distinction is why "Kafka failure" and "dead letter queue" are two different methods here
 * rather than one: the first breaks the producer, the second breaks the consumer, and they produce
 * completely different signals. A producer failure shows up immediately as an error on the sending
 * service; a poison message shows up as retries, then a growing dead-letter topic, and nothing at all
 * on the producer.
 */
public interface MessagingChaos {

    /**
     * Publishes a well-formed-looking message that the consumer cannot process.
     *
     * <p>The consumer should retry it according to its backoff policy, exhaust the attempts, and
     * publish it to the dead-letter topic with headers naming the original topic, partition, offset
     * and the exception. Nothing should consume it from there automatically — a poison message
     * replayed on a loop is how a dead-letter topic becomes an outage.
     *
     * @return the key of the published message, so a scenario can find it again
     */
    String publishPoisonMessage();

    /**
     * Attempts to publish a record larger than the broker's configured maximum, so the send fails
     * inside the producer.
     *
     * <p>A genuine producer-side failure that needs no broker outage: the connection is healthy, the
     * cluster is healthy, and the send is rejected before it leaves the JVM. That is a different
     * signal from an unreachable broker — no reconnection, no retry of a network error, just an
     * immediate {@code RecordTooLargeException} — and it is the shape of the failure that appears
     * when a payload grows past a limit nobody remembered was there.
     *
     * @return a description of what happened, including the exception if the send failed as intended
     */
    String publishOversizedMessage();

    /** The topic the poison message was published to. */
    String poisonTopic();

    /** The dead-letter topic it is expected to end up on. */
    String deadLetterTopic();
}

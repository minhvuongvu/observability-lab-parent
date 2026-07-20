#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Declares the topics the lab uses.
#
# Broker-side auto-creation is disabled on purpose: a topic is infrastructure
# with a partition count, a retention policy and a cleanup policy, none of
# which should be decided implicitly by whichever producer happens to connect
# first. Creation is idempotent, so re-running this is harmless.
# ---------------------------------------------------------------------------
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
REPLICATION="${TOPIC_REPLICATION:-1}"
TOPIC_CMD=/opt/kafka/bin/kafka-topics.sh

DAY_MS=86400000

create_topic() {
  local name="$1" retention_days="$2" description="$3"
  echo "  ${name}  (${PARTITIONS} partitions, ${retention_days}d retention) - ${description}"
  "${TOPIC_CMD}" \
    --bootstrap-server "${BOOTSTRAP}" \
    --create --if-not-exists \
    --topic "${name}" \
    --partitions "${PARTITIONS}" \
    --replication-factor "${REPLICATION}" \
    --config "retention.ms=$((retention_days * DAY_MS))" \
    --config cleanup.policy=delete
}

echo "Creating topics on ${BOOTSTRAP}"

create_topic order-created      7  "an order was accepted and needs stock reserved"
create_topic inventory-updated  7  "stock was adjusted; the order can be settled"
create_topic retry-topic        7  "delayed redelivery with backoff"
# Failures are kept far longer than successes: a dead letter is only useful if
# it is still there when somebody finally looks at the alert.
create_topic dead-letter-topic 30  "messages that exhausted their retries"

echo
echo "Topics now present:"
"${TOPIC_CMD}" --bootstrap-server "${BOOTSTRAP}" --list | sed 's/^/  /'

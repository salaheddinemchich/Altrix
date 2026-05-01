#!/bin/bash
# Waits for Kafka to be ready, then creates all required topics.
# Run once after docker compose up.

set -e

KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"
WAIT_SECONDS=30

echo "Waiting for Kafka at $KAFKA_BROKER..."
for i in $(seq 1 $WAIT_SECONDS); do
  if /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$KAFKA_BROKER" --list > /dev/null 2>&1; then
    echo "Kafka is ready."
    break
  fi
  echo "  attempt $i/$WAIT_SECONDS..."
  sleep 1
done

create_topic() {
  local name=$1
  local partitions=${2:-1}
  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$KAFKA_BROKER" \
    --create --if-not-exists \
    --topic "$name" \
    --partitions "$partitions" \
    --replication-factor 1
  echo "Topic ready: $name"
}

create_topic "project.registered"
create_topic "migration.job.created"
create_topic "migration.job.status.update"
create_topic "migration.job.completed"

echo "All topics created."
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$KAFKA_BROKER" --list

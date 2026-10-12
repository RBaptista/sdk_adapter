#!/usr/bin/env bash
#
# Runs ml-core-test-harness's monitoring stack (Prometheus, Grafana and its dashboards, cadvisor,
# node-exporter, Kafka and MySQL exporters) with the override in docker/monitoring, which points it
# at the containers of this hub. The harness checkout is not modified.
#
# Usage:   ./scripts/monitoring.sh [up|down|ps|targets]    (default: up)
#          HARNESS_DIR=/path/to/ml-core-test-harness ./scripts/monitoring.sh
#
# Grafana: http://localhost:9999 (admin / password)   Prometheus: http://localhost:9090/targets
#
# Needs: docker, curl, jq.

set -euo pipefail

HARNESS_DIR="${HARNESS_DIR:-$HOME/Documents/ml-core-test-harness}"
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export SDK_MONITORING_DIR="$SCRIPT_DIR/../docker/monitoring"
SDK_MONITORING_DIR=$(cd "$SDK_MONITORING_DIR" && pwd)

[[ -f $HARNESS_DIR/docker-compose-monitoring.yml ]] || {
  echo "ml-core-test-harness not found at $HARNESS_DIR (set HARNESS_DIR)" >&2; exit 1; }

# the harness's project name keeps the existing containers and volumes (Prometheus data, Grafana state).
# The hub runs under the same project, so compose lists its containers as "orphans": never pass
# --remove-orphans here, it would remove the hub.
#
# the profiles add the MySQL exporters for the central-ledger and ALS databases
compose() {
  docker compose -p ml-core-test-harness --project-directory "$HARNESS_DIR" \
    -f "$HARNESS_DIR/docker-compose-monitoring.yml" -f "$SDK_MONITORING_DIR/docker-compose.yml" \
    --profile transfers-test --profile als-test "$@"
}

# mysqld-exporter's user in the central-ledger database (the harness creates it only in mysql-als)
exporter_user() {
  docker exec mysql mysql -uroot -N -e "
    CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY 'password' WITH MAX_USER_CONNECTIONS 3;
    GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';" ||
    echo "warning: could not create the exporter user in mysql; the MySQL dashboard stays empty for central-ledger" >&2
}

targets() {
  curl -s localhost:9090/api/v1/targets |
    jq -r '.data.activeTargets[] | [.labels.job, .labels.instance, .health, (.lastError // "")[0:80]] | @tsv' |
    sort | column -t -s $'\t'
}

case "${1:-up}" in
  up)
    exporter_user
    compose up -d
    echo "waiting for the first scrapes..."; sleep 15
    targets
    echo
    echo "Grafana:    http://localhost:9999 (admin / password)"
    echo "Prometheus: http://localhost:9090/targets"
    ;;
  down)    compose down ;;
  ps)      compose ps ;;
  targets) targets ;;
  *)       echo "usage: $0 [up|down|ps|targets]" >&2; exit 1 ;;
esac

#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/.." && pwd)"

JAVA_XMS="${JAVA_XMS:-256m}"
JAVA_XMX="${JAVA_XMX:-384m}"
JAVA_GC="${JAVA_GC:-g1}"
JAVA_MAX_METASPACE="${JAVA_MAX_METASPACE:-128m}"
APP_JAR="${APP_JAR:-${repo_root}/target/datalevin-docs-standalone.jar}"

jvm_opts=("-Xms${JAVA_XMS}" "-Xmx${JAVA_XMX}" "-XX:MaxMetaspaceSize=${JAVA_MAX_METASPACE}")

case "${JAVA_GC}" in
  g1)
    jvm_opts+=("-XX:+UseG1GC" "-XX:MaxGCPauseMillis=200" "-XX:+UseStringDeduplication")
    ;;
  serial)
    jvm_opts+=("-XX:+UseSerialGC")
    ;;
  *)
    echo "Unsupported JAVA_GC: ${JAVA_GC}" >&2
    echo "Expected one of: g1, serial" >&2
    exit 1
    ;;
esac

if [[ ! -f "${APP_JAR}" ]]; then
  echo "Production jar not found: ${APP_JAR}" >&2
  echo "Build it first with: clojure -T:build uber" >&2
  exit 1
fi

exec java "${jvm_opts[@]}" -jar "${APP_JAR}" "$@"

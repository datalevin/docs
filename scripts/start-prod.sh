#!/usr/bin/env bash

set -euo pipefail

JAVA_XMS="${JAVA_XMS:-256m}"
JAVA_XMX="${JAVA_XMX:-384m}"
JAVA_GC="${JAVA_GC:-g1}"
JAVA_MAX_METASPACE="${JAVA_MAX_METASPACE:-128m}"

jvm_opts=("-J-Xms${JAVA_XMS}" "-J-Xmx${JAVA_XMX}" "-J-XX:MaxMetaspaceSize=${JAVA_MAX_METASPACE}")

case "${JAVA_GC}" in
  g1)
    jvm_opts+=("-J-XX:+UseG1GC" "-J-XX:MaxGCPauseMillis=200" "-J-XX:+UseStringDeduplication")
    ;;
  serial)
    jvm_opts+=("-J-XX:+UseSerialGC")
    ;;
  *)
    echo "Unsupported JAVA_GC: ${JAVA_GC}" >&2
    echo "Expected one of: g1, serial" >&2
    exit 1
    ;;
esac

exec clojure "${jvm_opts[@]}" -M:prod -m datalevin.docs.core "$@"

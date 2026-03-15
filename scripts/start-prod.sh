#!/usr/bin/env bash

set -euo pipefail

JAVA_XMS="${JAVA_XMS:-256m}"
JAVA_XMX="${JAVA_XMX:-512m}"

exec clojure "-J-Xms${JAVA_XMS}" "-J-Xmx${JAVA_XMX}" -M:prod -m datalevin.docs.core "$@"

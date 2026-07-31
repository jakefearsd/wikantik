#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# Tests for docker/docker-compose.embeddings.yml. Pure YAML/config assertions —
# does not start a container, so it runs anywhere.
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE="${REPO_DIR}/docker/docker-compose.embeddings.yml"
fails=0
check() { # check <description> <pattern>
  if grep -q -- "$2" "$COMPOSE"; then echo "  ok: $1"; else echo "  FAIL: $1 (no match for: $2)"; fails=$((fails+1)); fi
}
echo "test-embeddings: $COMPOSE"
[ -f "$COMPOSE" ] || { echo "  FAIL: compose file missing"; exit 1; }
check "uses the ollama image"            "image: ollama/ollama"
check "port is parameterised"            'WIKANTIK_EMBEDDING_PORT'
check "model tag is parameterised"       'WIKANTIK_EMBEDDING_MODEL_TAG'
check "defaults to qwen3-embedding:0.6b" 'qwen3-embedding:0.6b'
check "caches models in a named volume"  'ollama-models'
check "has a healthcheck"                'healthcheck:'
# The pull must be idempotent-on-restart, not a one-shot init container.
# Anchored to the actual invocation (not just the substring "ollama pull"),
# which also appears in a prose comment above the entrypoint script — a plain
# substring match would still pass if a future edit deleted the real
# invocation but left the comment behind.
check "pulls the model on start"         'ollama pull "\${WIKANTIK_EMBEDDING_MODEL_TAG'

TEMPLATE="${REPO_DIR}/wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template"
DEPLOY="${REPO_DIR}/bin/deploy-local.sh"
ENVEX="${REPO_DIR}/.env.example"
checkf() { # checkf <description> <file> <pattern>
  if grep -q -- "$3" "$2"; then echo "  ok: $1"; else echo "  FAIL: $1 (no match for: $3)"; fails=$((fails+1)); fi
}
checkf "template carries the base-url placeholder" "$TEMPLATE" '@@EMBEDDING_BASE_URL@@'
checkf "deploy-local substitutes it"               "$DEPLOY"   '@@EMBEDDING_BASE_URL@@'
checkf "deploy-local can be opted out"             "$DEPLOY"   'WIKANTIK_LOCAL_EMBEDDINGS'
# deploy-local.sh does NOT overwrite an existing properties file, so an existing
# install would otherwise never gain the new setting.
checkf "deploy-local repairs an existing props file" "$DEPLOY" 'embedding.base-url'
checkf ".env.example documents the base url"       "$ENVEX"    'WIKANTIK_EMBEDDING_BASE_URL'
checkf ".env.example documents the opt-out"        "$ENVEX"    'WIKANTIK_LOCAL_EMBEDDINGS'

if [ "$fails" -ne 0 ]; then echo "test-embeddings: ${fails} failure(s)"; exit 1; fi
echo "test-embeddings: all passed"

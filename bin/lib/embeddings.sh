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
#
# Shared, dependency-free logic for wiring the local-dev CPU-ollama embedding
# sidecar (docker/docker-compose.embeddings.yml) into a deployed Tomcat's
# wikantik-custom.properties. Sourced by bin/deploy-local.sh AND directly by
# bin/tests/test-embeddings.sh, which stubs `curl` on PATH and exercises
# these functions against temp files — no Docker, no live wiki required.
#
# Callers must have print_status()/print_warning() defined (deploy-local.sh
# defines both before sourcing this file; the test provides trivial stubs).

# The ini-bundle default (wikantik-main/src/main/resources/ini/wikantik.properties,
# ~line 1227) — the shared inference host it names was decommissioned. This is
# the ONLY value repair_embedding_base_url() will silently rewrite: nobody
# could be deliberately relying on a host that is provably, permanently dead.
# Any other existing value is left untouched — see repair_embedding_base_url.
WIKANTIK_KNOWN_DEAD_EMBEDDING_URL="http://inference.jakefear.com:11434"

# TRUE iff ${1}'s /api/tags response body actually contains the model tag
# ${2}. Deliberately NOT "the daemon answered HTTP 200" — Ollama returns 200
# with an empty model list while a pull is still running, so a bare
# reachability probe reports ready before the wiki can actually embed
# anything (the same weakness the container's own healthcheck was hardened
# against in Task 1).
embeddings_model_ready() {
    local base_url="$1" model_tag="$2"
    curl -sf --max-time 3 "${base_url%/}/api/tags" 2>/dev/null | grep -qF -- "${model_tag}"
}

# Ensures ${1} (an ALREADY-EXISTING wikantik-custom.properties file) doesn't
# silently keep a stale embedding base-url. Three cases, checked in order:
#
#   1. Key entirely absent       -> append it. First time this setting has
#                                    ever existed on this install; nothing to
#                                    preserve.
#   2. Key = the known-dead URL  -> rewrite it to ${2}, and say so. Safe to
#                                    do without asking: that host is provably
#                                    decommissioned, so no one can be
#                                    deliberately depending on it.
#   3. Key = anything else       -> NEVER rewritten — a developer may have
#                                    deliberately pointed this at their own
#                                    endpoint. If it isn't currently serving
#                                    ${3}, warn loudly (name the configured
#                                    value, name the exact fix) so the
#                                    operator has a clear signal instead of a
#                                    silent no-op; otherwise stay silent.
#
# Args: props_file desired_url model_tag
repair_embedding_base_url() {
    local props_file="$1" desired_url="$2" model_tag="$3"
    local existing_line existing_url

    existing_line="$(grep "^wikantik.search.embedding.base-url" "${props_file}" || true)"
    if [[ -z "${existing_line}" ]]; then
        printf '\n# Added by deploy-local.sh — local embeddings container.\nwikantik.search.embedding.base-url = %s\n' \
            "${desired_url}" >> "${props_file}"
        print_status "Added embedding base-url to the existing ${props_file}"
        return 0
    fi

    existing_url="$(printf '%s\n' "${existing_line}" | sed -E 's/^[^=]*=[[:space:]]*//')"

    if [[ "${existing_url}" == "${WIKANTIK_KNOWN_DEAD_EMBEDDING_URL}" ]]; then
        sed -i.bak -E "s|^(wikantik\.search\.embedding\.base-url[[:space:]]*=[[:space:]]*).*|\\1${desired_url}|" "${props_file}"
        rm -f "${props_file}.bak"
        print_warning "${props_file}: base-url was the decommissioned ${WIKANTIK_KNOWN_DEAD_EMBEDDING_URL} — corrected to ${desired_url}"
        return 0
    fi

    if [[ "${existing_url}" != "${desired_url}" ]] && ! embeddings_model_ready "${existing_url}" "${model_tag}"; then
        print_warning "${props_file}: embedding base-url is ${existing_url}, which is not currently serving ${model_tag}."
        echo "         If that is a deliberate custom endpoint you run yourself, ignore this warning."
        echo "         Otherwise edit ${props_file} and set:"
        echo "             wikantik.search.embedding.base-url = ${desired_url}"
    fi
}

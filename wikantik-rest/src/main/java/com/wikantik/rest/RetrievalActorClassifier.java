/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.rest;

import com.wikantik.api.core.Session;
import com.wikantik.api.querylog.ActorType;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Infers the {@link ActorType} of a {@code /api} retrieval request from the auth it carries:
 * an {@code Authorization} header (HTTP Basic / Bearer) means a programmatic caller — curl, the
 * ingest CLI, an agent — so {@code AGENT}; otherwise an authenticated session means the SPA, so
 * {@code HUMAN}; an anonymous request is {@code UNKNOWN}. (MCP / tools surfaces are agent by
 * construction and pass {@code AGENT} directly rather than going through here.)
 */
final class RetrievalActorClassifier {

    private RetrievalActorClassifier() {}

    static ActorType classify( final HttpServletRequest request, final Session session ) {
        if ( request != null && request.getHeader( "Authorization" ) != null ) {
            return ActorType.AGENT;
        }
        if ( session != null && session.isAuthenticated() ) {
            return ActorType.HUMAN;
        }
        return ActorType.UNKNOWN;
    }
}

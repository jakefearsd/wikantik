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
package com.wikantik.knowledge.mcp;

/**
 * Decides whether a {@code /knowledge-mcp} caller may view a given page.
 *
 * <p>The MCP transport exposes no per-call user identity — the SDK exchange carries no
 * {@code HttpServletRequest}, principal, or {@code WikiSession} — so the read-only retrieval
 * tools cannot filter by the caller's own permissions. Instead they enforce page view-ACLs as an
 * anonymous <b>guest</b>: only publicly-viewable pages are returned. Callers that legitimately need
 * restricted content use the privileged {@code /wikantik-admin-mcp} surface (AllPermission bypass).
 *
 * <p>The production gate is built once at startup in {@link KnowledgeMcpInitializer} from
 * {@code WikiSession.guestSession(engine)} + {@code PermissionFilter.canAccessQuietly(..., "view")}
 * — the same request-free guest-view-check the public RDF/ontology surface uses.
 */
@FunctionalInterface
public interface PageViewGate {

    /**
     * @param slug the page slug / name
     * @return {@code true} if a guest may view the page (or {@code slug} is null/blank, which
     *         callers handle as their own not-found case)
     */
    boolean canView( String slug );

    /**
     * Permits every page. Used as the backward-compatible default for the existing tool
     * constructors and in tests that are not exercising ACLs. The startup wiring always injects a
     * real guest-backed gate; {@code ALLOW_ALL} is the fail-safe only if a guest session cannot be
     * constructed (which is logged).
     */
    PageViewGate ALLOW_ALL = slug -> true;
}

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
package com.wikantik.auth.subsystem;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.Authorizer;
import com.wikantik.auth.SecurityVerifier;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import jakarta.servlet.ServletContext;

/**
 * Namespace for the Auth subsystem's input and output contracts.
 *
 * <p>Phase 4 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Auth wraps the four core security managers (authentication,
 * authorization, users, groups) plus the web-container authorizer and
 * the API-key service behind a typed surface. Phase 4 Checkpoint 1
 * keeps {@link SecurityVerifier} as a single field; Checkpoint 3
 * decomposes it into {@code PolicyVerifier}, {@code ContainerRoleVerifier},
 * and {@code JaasVerifier}.</p>
 */
public final class AuthSubsystem {

    private AuthSubsystem() {}

    /**
     * What the Auth subsystem requires from upstream.
     *
     * <p>{@code engine} is the legacy seam — JAAS LoginModules call back
     * through {@link Engine} during login flows, and the four auth
     * managers' {@code initialize(Engine, Properties)} contract still
     * needs an {@link Engine}. Subsequent phases narrow this dependency.</p>
     *
     * <p>{@code servletContext} may be {@code null} for non-servlet
     * engines (test harnesses); when null, the web-container authorizer
     * is unavailable and {@link Services#webAuthorizer()} comes back
     * {@code null}.</p>
     */
    public record Deps(
        CoreSubsystem.Services core,
        PersistenceSubsystem.Services persistence,
        ServletContext servletContext,
        Engine engine
    ) {}

    /**
     * What the Auth subsystem exposes to downstream consumers.
     *
     * <p>Every field is non-null after a successful
     * {@link AuthSubsystemFactory#create} call, except:</p>
     *
     * <ul>
     *   <li>{@code webAuthorizer} is {@code null} when the configured
     *       authorizer is not a {@link com.wikantik.auth.authorize.WebAuthorizer}
     *       (e.g. test fixtures that wire a stub authorizer).</li>
     *   <li>{@code apiKeys} is {@code null} when no JDBC datasource is
     *       configured (the legacy {@code ApiKeyServiceHolder} contract).</li>
     *   <li>{@code securityVerifier} is always {@code null} — it is
     *       session-scoped (a fresh {@link SecurityVerifier} per admin
     *       request) and does not fit the engine-scoped typed-services
     *       bundle. Callers must instantiate {@link SecurityVerifier}
     *       directly as before. This field is retained so that Checkpoint 5
     *       can remove it cleanly if it remains unused.</li>
     * </ul>
     */
    public record Services(
        AuthenticationManager authentication,
        AuthorizationManager  authorization,
        UserManager           users,
        GroupManager          groups,
        Authorizer            webAuthorizer,
        ApiKeyService         apiKeys,
        SecurityVerifier      securityVerifier
    ) {}
}

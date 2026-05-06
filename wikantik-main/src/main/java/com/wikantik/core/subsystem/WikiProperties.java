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
package com.wikantik.core.subsystem;

import java.util.Properties;

/**
 * Typed configuration accessor — the public face of wiki properties for code
 * that lives inside extracted subsystems.
 *
 * <p>Phase 2 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Every {@code get*} method below mirrors the precedence already in use
 * via {@code com.wikantik.util.TextUtil#get*Property}: a Java system
 * property of the same name wins, then an environment variable with
 * dots replaced by underscores, then the property in this object, then
 * the supplied default. This keeps behavior identical to the legacy
 * direct-{@code Properties} reads as consumers migrate.</p>
 *
 * <p>{@link #asProperties()} is a deliberate escape hatch for code that
 * passes the raw {@link Properties} instance through to a third-party
 * library. New code should not use it; the migration tracker counts
 * remaining {@code asProperties()} callers as a debt to retire in
 * later phases.</p>
 */
public interface WikiProperties {

    /** Returns the property value, or {@code null} if neither configured nor present. */
    String get( String key );

    /**
     * Returns the property value, falling back to {@code defaultValue} when
     * neither configured nor present. The returned value is trimmed.
     */
    String get( String key, String defaultValue );

    /** Returns the property as an int, falling back on missing/malformed values. */
    int getInt( String key, int defaultValue );

    /** Returns the property as a long, falling back on missing/malformed values. */
    long getLong( String key, long defaultValue );

    /**
     * Returns the property as a boolean. Recognizes {@code true/false},
     * {@code yes/no}, {@code on/off}; anything else is {@code false}.
     * Falls back to {@code defaultValue} on missing.
     */
    boolean getBoolean( String key, boolean defaultValue );

    /**
     * Escape hatch: returns the underlying {@link Properties} instance for
     * callers that hand the raw object to legacy APIs. Modifications to the
     * returned object are visible to other readers — treat as read-only.
     */
    Properties asProperties();

    /** Returns the names of properties currently set on this object. */
    Iterable< String > propertyNames();
}

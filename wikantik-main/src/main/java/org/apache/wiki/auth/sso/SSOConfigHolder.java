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
package org.apache.wiki.auth.sso;

import org.apache.wiki.api.core.Engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the {@link SSOConfig} instance for each wiki engine.
 * This avoids coupling the SSO config lifecycle to a specific manager class.
 *
 * @since 3.1
 */
public final class SSOConfigHolder {

    private static final Map< String, SSOConfig > CONFIGS = new ConcurrentHashMap<>();

    private SSOConfigHolder() {
    }

    /**
     * Stores the SSO config for an engine.
     *
     * @param engine the wiki engine
     * @param config the SSO config
     */
    public static void setConfig( final Engine engine, final SSOConfig config ) {
        CONFIGS.put( engine.getApplicationName(), config );
    }

    /**
     * Retrieves the SSO config for an engine.
     *
     * @param engine the wiki engine
     * @return the SSO config, or {@code null} if not initialized
     */
    public static SSOConfig getConfig( final Engine engine ) {
        return CONFIGS.get( engine.getApplicationName() );
    }

    /**
     * Removes the SSO config for an engine.
     *
     * @param engine the wiki engine
     */
    public static void removeConfig( final Engine engine ) {
        CONFIGS.remove( engine.getApplicationName() );
    }
}

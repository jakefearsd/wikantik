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
package com.wikantik.observability.health;

import com.wikantik.api.core.Engine;

/**
 * Checks that the WikiEngine has been initialized and is configured.
 */
public class EngineHealthCheck implements HealthCheck {

    private final Engine engine;

    public EngineHealthCheck( final Engine engine ) {
        this.engine = engine;
    }

    @Override
    public String name() {
        return "engine";
    }

    @Override
    public HealthResult check() {
        if ( engine == null ) {
            return HealthResult.down( "Engine is null" );
        }
        if ( !engine.isConfigured() ) {
            return HealthResult.down( "Engine is not configured" );
        }
        return HealthResult.up();
    }

}

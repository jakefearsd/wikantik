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
package com.wikantik.collab.adapter;

import com.wikantik.WikiEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.collab.CollabStore;

import java.util.Properties;

/**
 * Wires collaboration on a test engine over a given store (an {@code InMemoryCollabStore}, or a failing double) and the
 * real wiki adapters, exactly as {@link CollabWiring#wire(WikiEngine, Properties, javax.sql.DataSource, PageManager)}
 * does over the datasource: the registry is enabled, its page event listener registered, the wiring set on the engine.
 * The caller closes the returned wiring.
 */
public final class CollabTestWiring {

    private CollabTestWiring() {
    }

    public static CollabWiring wire( final WikiEngine engine, final CollabStore store ) {
        final Properties props = new Properties();
        props.setProperty( "wikantik.collab.enabled", "true" );
        return CollabWiring.wireWith( engine, props, store, engine.getManager( PageManager.class ) );
    }
}

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
import com.wikantik.api.core.Engine;
import com.wikantik.collab.CollabSessionRegistry;

/**
 * The one place that unwraps an {@link Engine} to its collaboration registry, for the engine code that must consult it
 * (the page renamer's draft guard, the user deletion's eviction) without a constructor parameter: their construction
 * sites stay as they are. The registry is read from the engine's service registry, never through {@code getManager}.
 */
public final class CollabEngineAccess {

    private CollabEngineAccess() {
    }

    /**
     * The collaboration registry wired on {@code engine}, or null when collaboration is not wired (no datasource, a wire
     * failure, or an engine that is not a {@link WikiEngine}). A wired registry may still be
     * {@link CollabSessionRegistry#isEnabled() disabled}: drafts stored while it was enabled still count.
     */
    public static CollabSessionRegistry registry( final Engine engine ) {
        if( engine instanceof WikiEngine we ) {
            final CollabWiring wiring = we.serviceRegistry().get( CollabWiring.class );
            return wiring == null ? null : wiring.registry();
        }
        return null;
    }
}

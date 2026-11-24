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
package com.wikantik.livevalues;

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.core.registry.EngineServiceRegistry;

/** Finds the registered {@link LiveValues} service on an engine, tolerating engines that have none (mocks, early init). */
public final class LiveValuesLookup {

    private LiveValuesLookup() {
    }

    /** @return the service, or null when the engine has no registry or nothing is registered yet */
    public static LiveValues of( final Engine engine ) {
        if( !( engine instanceof WikiEngine we ) ) {
            return null;
        }
        final EngineServiceRegistry registry = we.serviceRegistry();
        return registry == null ? null : registry.get( LiveValues.class );
    }
}

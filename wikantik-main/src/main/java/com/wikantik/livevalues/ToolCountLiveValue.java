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
import com.wikantik.api.livevalues.LiveValueExpander.Resolution;
import com.wikantik.api.livevalues.LiveValueSource;
import com.wikantik.api.livevalues.LiveValues;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Set;

/**
 * Publishes the number of tools an endpoint registered as a live value (for example {@code tools.admin}).
 * Endpoint initializers call {@link #register} once their tool list is built.
 */
public final class ToolCountLiveValue {

    private static final Logger LOG = LogManager.getLogger( ToolCountLiveValue.class );

    private ToolCountLiveValue() {
    }

    /**
     * Registers a source for {@code name} with value {@code count}; skipped (DEBUG) when the engine carries no
     * {@link LiveValues} service.
     *
     * @return true when a source was registered
     */
    public static boolean register( final Engine engine, final String name, final int count ) {
        final LiveValues liveValues = engine instanceof WikiEngine we ? we.serviceRegistry().get( LiveValues.class ) : null;
        if ( liveValues == null ) {
            LOG.debug( "No LiveValues service available - not publishing live value {}", name );
            return false;
        }
        final Map< String, Resolution > values = Map.of( name, new Resolution.Value( count ) );
        liveValues.register( new LiveValueSource() {
            @Override
            public Map< String, Resolution > values() {
                return values;
            }

            @Override
            public Set< String > declaredNames() {
                return Set.of( name );
            }
        } );
        return true;
    }
}

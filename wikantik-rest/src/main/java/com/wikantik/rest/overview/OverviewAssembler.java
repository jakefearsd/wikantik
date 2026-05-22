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
package com.wikantik.rest.overview;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Runs each card collector with per-card try/catch so one failure can't sink the page. */
public class OverviewAssembler {
    private static final Logger LOG = LogManager.getLogger( OverviewAssembler.class );
    private final Map<String, Supplier<JsonObject>> collectors;

    public OverviewAssembler( final Map<String, Supplier<JsonObject>> collectors ) {
        this.collectors = collectors;
    }

    public OverviewSnapshot assemble() {
        final JsonObject cards = new JsonObject();
        final List<String> degraded = new ArrayList<>();
        for ( final Map.Entry<String, Supplier<JsonObject>> e : collectors.entrySet() ) {
            try {
                cards.add( e.getKey(), e.getValue().get() );
            } catch ( final RuntimeException ex ) {
                LOG.warn( "Overview card '{}' failed to assemble: {}", e.getKey(), ex.toString() );
                degraded.add( e.getKey() );
            }
        }
        return new OverviewSnapshot( cards, degraded );
    }
}

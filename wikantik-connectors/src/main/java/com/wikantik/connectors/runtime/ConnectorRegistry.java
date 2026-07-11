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
package com.wikantik.connectors.runtime;

import com.wikantik.api.connectors.SourceConnector;
import java.util.*;

/** Immutable id → connector registry, built once at wiring time. */
public final class ConnectorRegistry {
    private final Map< String, SourceConnector > byId;
    private final Map< String, String > typeById;
    public ConnectorRegistry( final Map< String, SourceConnector > byId, final Map< String, String > typeById ) {
        this.byId = Map.copyOf( byId );
        this.typeById = Map.copyOf( typeById );
    }
    public Optional< SourceConnector > get( final String id ) { return Optional.ofNullable( byId.get( id ) ); }
    public Set< String > ids() { return byId.keySet(); }
    public String typeOf( final String id ) { return typeById.getOrDefault( id, "unknown" ); }
}

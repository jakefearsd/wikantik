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
package com.wikantik.derived;

import com.google.gson.JsonObject;
import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SourceConnector;

import java.util.Properties;

/**
 * Builds the {@link SourceConnector} for one connector type from that type's typed config record.
 * Implementations are stateless and registered in {@link ConnectorFactories}; {@link ConnectorAssembler}
 * dispatches to them by type and guarantees {@link #create} only ever sees an instance of
 * {@link #configClass()}.
 *
 * @param <C> the typed config record this connector type consumes
 */
public interface ConnectorFactory< C > {

    /** The connector type string this factory handles, e.g. {@code "feed"}; its registry key. */
    String type();

    /** The typed config record {@link #create} consumes. */
    Class< C > configClass();

    /** Builds the connector for {@code id}. Must not throw for a well-formed {@code config}; secrets
     *  are read lazily from {@code creds} so a later rotation takes effect without a rebuild. */
    SourceConnector create( String id, C config, CredentialStore creds );

    /** Fills admin-omitted fields of a config JSON about to be validated, in place. No-op by default;
     *  a type overrides it when a missing field has a deployment-derived default. */
    default void applyDefaults( final JsonObject config, final Properties wikiProperties ) {
        // no defaults for this type
    }
}

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

import com.wikantik.api.connectors.CredentialStore;
import com.wikantik.api.connectors.SourceConnector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/** One shared per-type connector-construction path, extracted from {@link ConnectorWiringHelper}
 *  so properties-defined connectors and DB-defined connectors build the exact same
 *  {@link SourceConnector} instances. Per-type construction lives in {@link ConnectorFactories}. */
public final class ConnectorAssembler {

    private static final Logger LOG = LogManager.getLogger( ConnectorAssembler.class );

    private ConnectorAssembler() {}

    /** Builds the {@link SourceConnector} for one type + config record, or {@code Optional.empty()}
     *  (with a {@code LOG.warn}) for an unrecognized {@code type} or a {@code config} that is not the
     *  record class of {@code type} (e.g. {@code WebCrawlerConfig} for {@code "webcrawler"}). Never throws
     *  for a mismatched pairing, so a bad row cannot fail wiring. */
    public static Optional< SourceConnector > build( final String id, final String type, final Object config,
            final CredentialStore credStore ) {
        final Optional< ConnectorFactory< ? > > factory = ConnectorFactories.forType( type );
        if ( factory.isEmpty() ) {
            LOG.warn( "connector '{}': unknown type '{}' — skipping", id, type );
            return Optional.empty();
        }
        return create( factory.get(), id, type, config, credStore );
    }

    private static < C > Optional< SourceConnector > create( final ConnectorFactory< C > factory, final String id,
            final String type, final Object config, final CredentialStore credStore ) {
        if ( !factory.configClass().isInstance( config ) ) {
            LOG.warn( "connector '{}': type '{}' needs a {} config but got {} — skipping", id, type,
                factory.configClass().getSimpleName(), config == null ? "null" : config.getClass().getSimpleName() );
            return Optional.empty();
        }
        return Optional.of( factory.create( id, factory.configClass().cast( config ), credStore ) );
    }
}

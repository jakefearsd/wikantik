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
package com.wikantik.mcp.tools;

import com.wikantik.api.knowledge.Provenance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parser for the {@code provenance_filter} MCP argument, behind
 * {@link McpToolUtils#parseProvenanceFilter(Map)}.
 */
final class McpProvenanceFilter {

    private static final Logger LOG = LogManager.getLogger( McpProvenanceFilter.class );

    private McpProvenanceFilter() {
    }

    static Set< Provenance > parse( final Map< String, Object > arguments ) {
        final Object raw = arguments.get( "provenance_filter" );
        if ( !( raw instanceof List< ? > list ) || list.isEmpty() ) {
            return null;
        }
        final Set< Provenance > result = new LinkedHashSet<>();
        for ( final Object item : list ) {
            if ( item instanceof String s ) {
                try {
                    result.add( Provenance.fromValue( s ) );
                } catch ( final IllegalArgumentException ignored ) {
                    LOG.debug( "Skipping unknown provenance value '{}': {}", s, ignored.getMessage() );
                    // Unknown provenance strings are skipped (logged at debug above).
                }
            }
        }
        return result.isEmpty() ? null : result;
    }
}

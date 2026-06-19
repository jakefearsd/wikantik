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
package com.wikantik.api.querylog;

/**
 * The retrieval entry point a logged query came through — so traffic can be split by
 * surface when grounding the eval corpus (human SPA search vs agent bundle, etc.).
 */
public enum SourceSurface {

    API_BUNDLE( "api_bundle" ),
    API_SEARCH( "api_search" ),
    MCP_ASSEMBLE_BUNDLE( "mcp_assemble_bundle" ),
    TOOLS_SEARCH_WIKI( "tools_search_wiki" );

    private final String wire;

    SourceSurface( final String wire ) {
        this.wire = wire;
    }

    /** Stable lowercase token stored in {@code retrieval_query_log.source_surface}. */
    public String wire() {
        return wire;
    }

    /** Parse a stored wire token; throws on an unrecognised value (surfaces are a closed set we write). */
    public static SourceSurface fromWire( final String s ) {
        if ( s != null ) {
            for ( final SourceSurface v : values() ) {
                if ( v.wire.equals( s ) ) {
                    return v;
                }
            }
        }
        throw new IllegalArgumentException( "Unknown source surface: " + s );
    }
}

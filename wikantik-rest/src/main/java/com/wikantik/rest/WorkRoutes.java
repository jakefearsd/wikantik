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
package com.wikantik.rest;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.List;

/** The route table of a work servlet: patterns compiled from {@code routes()}, and matching a request to one. */
final class WorkRoutes {

    record Route( String method, String[] pattern, WorkResourceBase.Handler handler ) {
    }

    private WorkRoutes() {
    }

    /** Compiles {@code {method, pattern, handler}} triples; {@code {}} in a pattern captures a segment. */
    static List< Route > compile( final List< Object[] > raw ) {
        final List< Route > t = new ArrayList<>();
        for ( final Object[] r : raw ) {
            final String pattern = ( String ) r[ 1 ];
            t.add( new Route( ( String ) r[ 0 ], pattern.isEmpty() ? new String[ 0 ] : pattern.split( "/" ),
                ( WorkResourceBase.Handler ) r[ 2 ] ) );
        }
        return t;
    }

    static String[] segments( final HttpServletRequest request ) {
        final String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        final String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        String path = servletPath + pathInfo;
        final String prefix = "/api/work";
        if ( path.startsWith( prefix ) ) {
            path = path.substring( prefix.length() );
        }
        final List< String > out = new ArrayList<>();
        for ( final String s : path.split( "/" ) ) {
            if ( !s.isEmpty() ) {
                out.add( s );
            }
        }
        return out.toArray( new String[ 0 ] );
    }

    static List< String > match( final Route r, final String method, final String[] segs ) {
        if ( !r.method().equals( method ) || r.pattern().length != segs.length ) {
            return null;
        }
        final List< String > captured = new ArrayList<>();
        for ( int i = 0; i < segs.length; i++ ) {
            if ( "{}".equals( r.pattern()[ i ] ) ) {
                captured.add( segs[ i ] );
            } else if ( !r.pattern()[ i ].equals( segs[ i ] ) ) {
                return null;
            }
        }
        return captured;
    }
}

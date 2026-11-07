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
package com.wikantik.war;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 of MultiEdit adds a long-poll servlet under {@code /api/collab/*} that calls
 * {@code request.startAsync()}; the container throws unless every filter in the chain for that
 * path is declared {@code async-supported}. This test resolves the chain from web.xml plus the
 * http module's web-fragment.xml and names every filter that is not.
 */
class ApiFilterChainAsyncTest {

    private static final String PATH = "/api/collab/Page/updates";

    @Test
    void everyFilterOnTheCollabPathIsAsyncSupported() throws Exception {
        final List< Document > docs = List.of(
                parse( Path.of( "src", "main", "webapp", "WEB-INF", "web.xml" ) ),
                parse( Path.of( "..", "wikantik-http", "src", "main", "resources", "META-INF", "web-fragment.xml" ) ) );

        final Map< String, Boolean > async = new HashMap<>();
        final Map< String, String > servletPatterns = new HashMap<>(); // pattern -> servlet-name
        for ( final Document d : docs ) {
            for ( final Element f : elements( d, "filter" ) ) {
                async.put( text( f, "filter-name" ), "true".equals( text( f, "async-supported" ) ) );
            }
            for ( final Element m : elements( d, "servlet-mapping" ) ) {
                for ( final String p : texts( m, "url-pattern" ) ) {
                    servletPatterns.put( p, text( m, "servlet-name" ) );
                }
            }
        }
        final String servlet = servletFor( servletPatterns );

        final TreeSet< String > offenders = new TreeSet<>();
        final TreeSet< String > matched = new TreeSet<>();
        for ( final Document d : docs ) {
            for ( final Element fm : elements( d, "filter-mapping" ) ) {
                final String name = text( fm, "filter-name" );
                boolean applies = texts( fm, "url-pattern" ).stream().anyMatch( p -> matches( p, PATH ) );
                final String sn = text( fm, "servlet-name" );
                if ( sn != null && sn.equals( servlet ) ) {
                    applies = true;
                }
                if ( applies ) {
                    matched.add( name );
                }
                if ( applies && !Boolean.TRUE.equals( async.get( name ) ) ) {
                    offenders.add( name );
                }
            }
        }
        assertTrue( matched.size() >= 5, "chain resolution looks broken; matched only " + matched );
        assertTrue( offenders.isEmpty(), "Filters on " + PATH + " that are not async-supported: " + offenders );
    }

    private static String servletFor( final Map< String, String > patterns ) {
        String best = null;
        int bestLen = -1;
        for ( final Map.Entry< String, String > e : patterns.entrySet() ) {
            final String p = e.getKey();
            if ( matches( p, PATH ) && !p.equals( "/*" ) && p.length() > bestLen ) {
                best = e.getValue();
                bestLen = p.length();
            }
        }
        return best;
    }

    /** Servlet-spec url-pattern matching: exact, /prefix/*, *.ext. A lone "/" is an exact match for the context root in a
     * filter-mapping (Tomcat's behaviour), never a catch-all. */
    static boolean matches( final String pattern, final String path ) {
        if ( pattern.startsWith( "*." ) ) {
            return path.endsWith( pattern.substring( 1 ) );
        }
        if ( pattern.endsWith( "/*" ) ) {
            final String prefix = pattern.substring( 0, pattern.length() - 2 );
            return path.equals( prefix ) || path.startsWith( prefix + "/" );
        }
        return pattern.equals( path );
    }

    private static Document parse( final Path p ) throws Exception {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware( false );
        return f.newDocumentBuilder().parse( Files.newInputStream( p ) );
    }

    private static List< Element > elements( final Document d, final String tag ) {
        return children( d.getElementsByTagName( tag ) );
    }

    private static List< Element > children( final NodeList nl ) {
        final List< Element > out = new ArrayList<>();
        for ( int i = 0; i < nl.getLength(); i++ ) {
            out.add( ( Element ) nl.item( i ) );
        }
        return out;
    }

    private static List< String > texts( final Element e, final String tag ) {
        final List< String > out = new ArrayList<>();
        for ( final Element c : children( e.getElementsByTagName( tag ) ) ) {
            out.add( c.getTextContent().trim() );
        }
        return out;
    }

    private static String text( final Element e, final String tag ) {
        final List< String > t = texts( e, tag );
        return t.isEmpty() ? null : t.get( 0 );
    }
}

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
package com.wikantik.scim;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the minimal SCIM filter subset IdPs use: {@code <attr> eq "<value>"}
 *  for {@code userName} and {@code externalId} only. */
public final class ScimFilterParser {

    public record Eq( String attribute, String value ) {}

    public static final class UnsupportedFilterException extends RuntimeException {
        public UnsupportedFilterException( final String m ) { super( m ); }
    }

    private static final Set<String> SUPPORTED = Set.of( "userName", "externalId", "displayName" );
    private static final Pattern EQ = Pattern.compile( "^\\s*(\\w+)\\s+eq\\s+\"([^\"]*)\"\\s*$" );

    private ScimFilterParser() {}

    public static Optional<Eq> parse( final String filter ) {
        if ( filter == null || filter.isBlank() ) return Optional.empty();
        final Matcher m = EQ.matcher( filter );
        if ( !m.matches() ) {
            throw new UnsupportedFilterException( "Only '<attr> eq \"value\"' is supported: " + filter );
        }
        final String attr = m.group( 1 );
        if ( !SUPPORTED.contains( attr ) ) {
            throw new UnsupportedFilterException( "Filtering on '" + attr + "' is not supported" );
        }
        return Optional.of( new Eq( attr, m.group( 2 ) ) );
    }
}

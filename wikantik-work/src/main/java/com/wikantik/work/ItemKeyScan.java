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
package com.wikantik.work;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds item keys and pull-request numbers in free text: branch names, titles, commit messages. */
final class ItemKeyScan {

    static final int MAX_KEYS = 10;

    private static final Pattern KEY = Pattern.compile( "(?<![A-Z0-9])[A-Z][A-Z0-9]{1,9}-[1-9][0-9]{0,8}(?![0-9])" );
    private static final Pattern NUMBER = Pattern.compile( "#(\\d{1,9})" );

    private ItemKeyScan() {
    }

    /** The distinct keys in order of appearance, at most {@link #MAX_KEYS}; null gives none. */
    static List< String > keys( final String text ) {
        if ( text == null ) {
            return List.of();
        }
        final Set< String > found = new LinkedHashSet<>();
        final Matcher m = KEY.matcher( text );
        while ( m.find() && found.size() < MAX_KEYS ) {
            found.add( m.group() );
        }
        return List.copyOf( found );
    }

    /** Every {@code #12} in the message: a squash's {@code (#12)} or a merge's {@code Merge pull request #12}. */
    static Set< String > reviewNumbers( final String message ) {
        final Set< String > out = new LinkedHashSet<>();
        if ( message == null ) {
            return out;
        }
        final Matcher m = NUMBER.matcher( message );
        while ( m.find() ) {
            out.add( m.group( 1 ) );
        }
        return out;
    }
}

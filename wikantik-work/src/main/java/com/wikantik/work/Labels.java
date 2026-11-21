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

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Normalises and checks the labels on an item: trimmed, lowercase, distinct, sorted, at most {@link #MAX}. */
public final class Labels {

    /** The most distinct labels one item may carry. */
    public static final int MAX = 10;

    private static final Pattern LABEL = Pattern.compile( "[a-z0-9][a-z0-9._-]{0,39}" );
    private static final int SHOWN = 50;

    private Labels() {
    }

    /**
     * The labels as stored: each element trimmed and lower-cased, duplicates removed, sorted. Null or empty input
     * gives no labels.
     *
     * @throws WorkValidationException on field {@code labels} when an element is null or malformed, or when more
     *         than {@link #MAX} distinct labels remain
     */
    public static List< String > normalise( final Collection< String > raw ) {
        if ( raw == null || raw.isEmpty() ) {
            return List.of();
        }
        final TreeSet< String > distinct = new TreeSet<>();
        for ( final String element : raw ) {
            distinct.add( normaliseOne( element ) );
        }
        if ( distinct.size() > MAX ) {
            throw new WorkValidationException( "labels", "An item has at most " + MAX + " labels." );
        }
        return List.copyOf( distinct );
    }

    private static String normaliseOne( final String element ) {
        final String folded = element == null ? null : element.trim().toLowerCase( Locale.ROOT );
        if ( folded == null || !LABEL.matcher( folded ).matches() ) {
            final String shown = String.valueOf( element );
            throw new WorkValidationException( "labels",
                "A label is 1 to 40 lowercase letters, digits, dots, dashes or underscores, starting with a letter "
                    + "or digit: " + shown.substring( 0, Math.min( SHOWN, shown.length() ) ) );
        }
        return folded;
    }
}

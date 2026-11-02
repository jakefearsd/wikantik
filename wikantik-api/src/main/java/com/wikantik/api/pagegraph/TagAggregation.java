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
package com.wikantik.api.pagegraph;

import com.wikantik.api.markdown.InlineTagSyntax;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tag counting over page descriptors. Pages are grouped by normalised tag identity
 * ({@link InlineTagSyntax#normalise}) so {@code Foo}, {@code foo} and an inline {@code #FOO} are one tag counted once
 * per page; the name shown is the form most pages wrote (ties: the lexicographically smaller).
 */
public final class TagAggregation {

    private static final int SAMPLE_IDS = 10;

    private TagAggregation() {
    }

    private static final class Bucket {
        final Map< String, Integer > forms = new HashMap<>();
        final List< String > pageIds = new ArrayList<>();

        String display() {
            return forms.entrySet().stream()
                    .max( Map.Entry.< String, Integer >comparingByValue().thenComparing( Map.Entry.comparingByKey( Comparator.reverseOrder() ) ) )
                    .map( Map.Entry::getKey ).orElse( "" );
        }
    }

    private static Map< String, Bucket > buckets( final Collection< PageDescriptor > pages ) {
        final Map< String, Bucket > out = new LinkedHashMap<>();
        for ( final PageDescriptor p : pages ) {
            final Map< String, String > written = new LinkedHashMap<>();
            for ( final String w : p.writtenTags() ) {
                written.put( InlineTagSyntax.normalise( w ), w );
            }
            written.forEach( ( key, form ) -> {
                final Bucket b = out.computeIfAbsent( key, k -> new Bucket() );
                b.forms.merge( form, 1, Integer::sum );
                b.pageIds.add( p.canonicalId() );
            } );
        }
        return out;
    }

    /** Tag dictionary entries for tags on at least {@code minPages} of {@code pages}, most used first. */
    public static List< TagSummary > summaries( final Collection< PageDescriptor > pages, final int minPages ) {
        final int threshold = Math.max( 1, minPages );
        return buckets( pages ).values().stream()
                .filter( b -> b.pageIds.size() >= threshold )
                .map( b -> new TagSummary( b.display(), b.pageIds.size(), b.pageIds.stream().limit( SAMPLE_IDS ).toList() ) )
                .sorted( Comparator.comparingInt( TagSummary::count ).reversed().thenComparing( TagSummary::tag ) )
                .toList();
    }

    /** Displayed tag to page count over {@code pages}. */
    public static Map< String, Integer > distribution( final Collection< PageDescriptor > pages ) {
        final Map< String, Integer > out = new TreeMap<>();
        buckets( pages ).values().forEach( b -> out.put( b.display(), b.pageIds.size() ) );
        return out;
    }
}

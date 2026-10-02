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
package com.wikantik.importer;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Test double for {@link WikiSnapshot}: exact, then case-insensitive lowest, like the real one. */
public final class FakeWikiSnapshot implements WikiSnapshot {

    public static final FakeWikiSnapshot EMPTY = new FakeWikiSnapshot( Set.of(), Set.of(), Map.of() );

    private final Set< String > pages;
    private final Set< String > systemPages;
    private final Map< String, String > hubsByCluster;

    public FakeWikiSnapshot( final Set< String > pages, final Set< String > systemPages,
                             final Map< String, String > hubsByCluster ) {
        this.pages = new TreeSet<>( pages );
        this.systemPages = Set.copyOf( systemPages );
        this.hubsByCluster = Map.copyOf( hubsByCluster );
    }

    @Override
    public Optional< String > existingPage( final String name ) {
        if ( pages.contains( name ) ) {
            return Optional.of( name );
        }
        return pages.stream().filter( p -> p.equalsIgnoreCase( name ) ).findFirst();
    }

    @Override
    public boolean isSystemPage( final String name ) {
        return systemPages.contains( name );
    }

    @Override
    public Optional< String > hubPage( final String cluster ) {
        return Optional.ofNullable( hubsByCluster.get( cluster ) );
    }
}

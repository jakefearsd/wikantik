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
package com.wikantik.knowledge.bundle;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Slug → citation-identity resolvers used to build a {@link com.wikantik.api.bundle.CitationHandle}
 * per kept section: {@code canonicalIdOf} (a page without one can't be cited and its section is
 * dropped, not emitted) and {@code versionOf} (the pinned page version recorded on the citation).
 * {@code sectionLinksOf} turns a slug into that page's heading-path → section-URL lookup; it is
 * called once per kept page per bundle, so it may read and parse the page.
 */
record CitationResolvers( Function< String, Optional< String > > canonicalIdOf,
                          Function< String, Integer > versionOf,
                          Function< String, Function< List< String >, String > > sectionLinksOf ) {

    /** Resolvers that give sections no link. */
    CitationResolvers( final Function< String, Optional< String > > canonicalIdOf,
                       final Function< String, Integer > versionOf ) {
        this( canonicalIdOf, versionOf, slug -> headingPath -> null );
    }
}

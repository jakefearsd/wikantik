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
package com.wikantik.api.bundle;

import java.util.List;

/**
 * One ranked, cited evidence section in a context bundle.
 *
 * @param url link to the section on the wiki, for citing to a reader: absolute when
 *            {@code wikantik.baseURL} is set, else site-relative; its {@code #fragment} is the page
 *            view's anchor for the section, omitted when the section has none. Null when the
 *            assembler had no way to resolve links.
 */
public record BundleSection(
    String canonicalId, String slug, List< String > headingPath, String text,
    double score, CitationHandle citation, String url
) {
    public BundleSection {
        if ( slug == null || slug.isBlank() ) {
            throw new IllegalArgumentException( "slug must not be blank" );
        }
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
        text = text == null ? "" : text;
    }

    /** A section without a link. */
    public BundleSection( final String canonicalId, final String slug, final List< String > headingPath,
                          final String text, final double score, final CitationHandle citation ) {
        this( canonicalId, slug, headingPath, text, score, citation, null );
    }
}

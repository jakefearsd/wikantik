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
package com.wikantik.wikilink;

import com.wikantik.export.HeadingSlugs;
import com.wikantik.parser.markdown.BlockIds;

/**
 * The URL fragment a wikilink's {@code #heading} part produces. A heading starting with {@code ^} is a block reference
 * and its fragment is the heading itself ({@code ^abc}), never slugged: the rendered block carries {@code id="^abc"}.
 * Every server consumer of a wikilink's heading (rendered links, embeds, {@code ?format=md}) goes through here, and the
 * frontend's {@code wikiLinkHref} mirrors it (the shared {@code wikilinks.json} fixture asserts both).
 */
public final class WikiLinkFragments {

    private WikiLinkFragments() {
    }

    /**
     * @param heading the part after {@code #}; not {@code null}
     * @return {@code heading} for a block reference, else its heading slug
     */
    public static String of( final String heading ) {
        return BlockIds.isBlockRef( heading ) ? heading : HeadingSlugs.slug( heading );
    }
}

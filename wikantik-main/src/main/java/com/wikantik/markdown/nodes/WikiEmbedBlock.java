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
package com.wikantik.markdown.nodes;

import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.sequence.BasedSequence;


/**
 * A block-level page embed ({@code ![[Page]]} / {@code ![[Page#Heading]]} alone in its paragraph). A parse-time
 * placeholder only: it carries the target, never rendered HTML, so parsing loads no page and a parsed document is
 * viewer-neutral (safe to cache). The renderer transcludes the body per render, with that render's viewer, and
 * falls back to {@link #fallbackLink()} (the node {@code [[Page#Heading]]} would produce) when it may not.
 */
public class WikiEmbedBlock extends Block {

    private final String target;
    private final String heading;
    private final NativeWikiLinkNode fallbackLink;

    public WikiEmbedBlock( final String target, final String heading, final NativeWikiLinkNode fallbackLink ) {
        super( BasedSequence.NULL );
        this.target = target;
        this.heading = heading;
        this.fallbackLink = fallbackLink;
    }

    /** The embedded page name as written. */
    public String target() {
        return target;
    }

    /** The embedded section heading, or null for the whole page. */
    public String heading() {
        return heading;
    }

    /** The plain page link rendered in place of the embed (plugins disabled, embed budget spent). */
    public NativeWikiLinkNode fallbackLink() {
        return fallbackLink;
    }

    @Override
    public BasedSequence[] getSegments() {
        return EMPTY_SEGMENTS;
    }

}

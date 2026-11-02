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
package com.wikantik.markdown.extensions.inlinetags;

import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jetbrains.annotations.NotNull;

/** An Obsidian inline {@code #tag}: the {@code #} and the tag as written, rendered as a link to the tag view. */
public class InlineTag extends Node {

    private final BasedSequence text;

    /** @param text the {@code #} and the tag characters, exactly as the author wrote them */
    public InlineTag( final BasedSequence text ) {
        super( text );
        this.text = text;
    }

    @Override
    public @NotNull BasedSequence[] getSegments() {
        return new BasedSequence[] { text };
    }

    /** The tag as written, without the {@code #}. */
    public String written() {
        return text.subSequence( 1 ).toString();
    }
}

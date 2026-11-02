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
package com.wikantik.markdown.extensions.highlight;

import com.vladsch.flexmark.util.ast.DelimitedNode;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jetbrains.annotations.NotNull;

/** An Obsidian {@code ==highlight==}: inline content between two {@code ==} delimiters, rendered as {@code <mark>}. */
public class Highlight extends Node implements DelimitedNode {

    private BasedSequence openingMarker = BasedSequence.NULL;
    private BasedSequence text = BasedSequence.NULL;
    private BasedSequence closingMarker = BasedSequence.NULL;

    public Highlight( final BasedSequence openingMarker, final BasedSequence text, final BasedSequence closingMarker ) {
        super( openingMarker.baseSubSequence( openingMarker.getStartOffset(), closingMarker.getEndOffset() ) );
        this.openingMarker = openingMarker;
        this.text = text;
        this.closingMarker = closingMarker;
    }

    @Override
    public @NotNull BasedSequence[] getSegments() {
        return new BasedSequence[] { openingMarker, text, closingMarker };
    }

    @Override
    public BasedSequence getOpeningMarker() {
        return openingMarker;
    }

    @Override
    public void setOpeningMarker( final BasedSequence openingMarker ) {
        this.openingMarker = openingMarker;
    }

    @Override
    public BasedSequence getText() {
        return text;
    }

    @Override
    public void setText( final BasedSequence text ) {
        this.text = text;
    }

    @Override
    public BasedSequence getClosingMarker() {
        return closingMarker;
    }

    @Override
    public void setClosingMarker( final BasedSequence closingMarker ) {
        this.closingMarker = closingMarker;
    }
}

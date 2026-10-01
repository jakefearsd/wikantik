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
package com.wikantik.markdown.extensions.callouts;

import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.sequence.BasedSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A blockquote that opened with {@code [!type]}: first child is its {@link CalloutTitle}, the rest its body. */
public class CalloutBlock extends Block {
    public enum Fold { NONE, COLLAPSED, EXPANDED }

    private final String rawType;
    private final String style;
    private final Fold fold;

    public CalloutBlock( final BasedSequence chars, final String rawType, final Fold fold ) {
        super( chars );
        this.rawType = rawType;
        this.style = CalloutTypes.styleOf( rawType );
        this.fold = fold;
    }

    public String rawType() { return rawType; }
    public String style() { return style; }
    public Fold fold() { return fold; }

    public CalloutTitle title() {
        return ( CalloutTitle ) getFirstChild();
    }

    public String titleText() {
        final CalloutTitle t = title();
        final StringBuilder collected = new StringBuilder();
        for ( Node c = t.getFirstChild(); c != null; c = c.getNext() ) {
            collected.append( new TextCollectingVisitor().collectAndGetText( c ) );
        }
        final String text = collected.toString().trim();
        return text.isEmpty() ? CalloutTypes.defaultTitle( rawType ) : text;
    }

    /** Tag names the title's inline nodes render as (e.g. {@code strong}), for the parity fixture. */
    public List< String > titleTagNames() {
        final List< String > out = new ArrayList<>();
        for ( final Node n : title().getDescendants() ) {
            final String simple = n.getClass().getSimpleName();
            switch ( simple ) {
                case "StrongEmphasis" -> out.add( "strong" );
                case "Emphasis" -> out.add( "em" );
                case "Code" -> out.add( "code" );
                case "Link" -> out.add( "a" );
                default -> { }
            }
        }
        return out;
    }

    public String contentTextExcludingNested() {
        final StringBuilder sb = new StringBuilder();
        for ( Node c = title().getNext(); c != null; c = c.getNext() ) {
            if ( !( c instanceof CalloutBlock ) ) {
                sb.append( ' ' ).append( new TextCollectingVisitor().collectAndGetText( c ) );
            }
        }
        return sb.toString().replaceAll( "\\s+", " ").trim();
    }

    @Override
    public BasedSequence[] getSegments() {
        return EMPTY_SEGMENTS;
    }

    @Override
    public String toString() {
        return "CalloutBlock[" + style + "/" + rawType.toLowerCase( Locale.ROOT ) + "/" + fold + "]";
    }
}

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

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.parser.block.NodePostProcessor;
import com.vladsch.flexmark.parser.block.NodePostProcessorFactory;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites {@code > [!type][+|-] Title} blockquotes into {@link CalloutBlock}s. Never throws on odd input. */
public class CalloutPostProcessor extends NodePostProcessor {

    /** {@code [!type]} then an optional fold marker, then the optional title (rest of the first line). */
    static final Pattern MARKER = Pattern.compile( "^\\[!([A-Za-z][A-Za-z0-9_-]*)]([+-]?)[ \\t]*" );

    @Override
    public void process( final NodeTracker state, final Node node ) {
        if ( !( node instanceof BlockQuote bq ) || !( bq.getFirstChild() instanceof Paragraph p ) ) {
            return;
        }
        final String paragraph = p.getChars().toString();
        final Matcher m = MARKER.matcher( paragraph );
        if ( !m.find() ) {
            return;
        }
        final CalloutBlock.Fold fold = switch ( m.group( 2 ) ) {
            case "-" -> CalloutBlock.Fold.COLLAPSED;
            case "+" -> CalloutBlock.Fold.EXPANDED;
            default -> CalloutBlock.Fold.NONE;
        };
        // A non-Text inline node straddling the marker end (e.g. "[!note](url)") is a malformed marker:
        // bail out before mutating anything so the blockquote stays a plain blockquote.
        final int markerEnd = p.getStartOffset() + m.end();
        for ( Node c = p.getFirstChild(); c != null && !( c instanceof SoftLineBreak || c instanceof HardLineBreak );
              c = c.getNext() ) {
            if ( !( c instanceof Text ) && c.getStartOffset() < markerEnd && c.getEndOffset() > markerEnd ) {
                return;
            }
        }
        final CalloutBlock callout = new CalloutBlock( bq.getChars(), m.group( 1 ), fold );
        final CalloutTitle title = new CalloutTitle();
        callout.appendChild( title );

        // Move the first line's inline nodes (minus the marker) into the title.
        final int titleStart = p.getStartOffset() + m.end();
        final List< Node > firstLine = new ArrayList<>();
        Node lineBreak = null;
        for ( Node c = p.getFirstChild(); c != null; c = c.getNext() ) {
            if ( c instanceof SoftLineBreak || c instanceof HardLineBreak ) {
                lineBreak = c;
                break;
            }
            firstLine.add( c );
        }
        for ( final Node c : firstLine ) {
            if ( c.getEndOffset() <= titleStart ) {
                c.unlink();
            } else if ( c.getStartOffset() < titleStart ) {
                if ( c instanceof Text t ) {
                    t.setChars( t.getChars().subSequence( titleStart - t.getStartOffset() ) );
                    title.appendChild( t );   // appendChild unlinks from the paragraph
                } else {
                    c.unlink();
                }
            } else {
                title.appendChild( c );
            }
        }
        if ( lineBreak != null ) {
            lineBreak.unlink();
        }
        if ( !p.hasChildren() ) {
            p.unlink();
        }

        // Body: everything left in the blockquote, in order.
        while ( bq.getFirstChild() != null ) {
            callout.appendChild( bq.getFirstChild() );
        }
        bq.insertBefore( callout );
        bq.unlink();
        state.nodeRemoved( bq );
        state.nodeAddedWithChildren( callout );
    }

    public static class Factory extends NodePostProcessorFactory {
        public Factory() {
            super( false );
            addNodes( BlockQuote.class );
        }

        @Override
        public NodePostProcessor apply( final Document document ) {
            return new CalloutPostProcessor();
        }
    }
}

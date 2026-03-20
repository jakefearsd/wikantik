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
package com.wikantik.parser.markdown;

import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
import com.wikantik.api.core.Context;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.UserManager;
import com.wikantik.frontmatter.FrontmatterParser;
import com.wikantik.frontmatter.ParsedPage;
import com.wikantik.parser.MarkupParser;
import com.wikantik.parser.WikiDocument;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Map;
import java.util.regex.Pattern;


/**
 * Class handling the markdown parsing.
 */
public class MarkdownParser extends MarkupParser {

    private final Parser parser;

    /** Bare Flexmark parser (no JSPWiki extensions) used solely for link collection. */
    private static final Parser LINK_SCANNER = Parser.builder().build();

    /**
     * Matches wiki-syntax bracket references like [{Plugin}], [{ALLOW view Admin}], [{$var}], [{SET k=v}]
     * that are NOT already followed by (). The replacement adds () so Flexmark parses them as inline links
     * rather than link references (which conflict with the Attributes extension).
     */
    private static final Pattern WIKI_BRACKET_REF = Pattern.compile( "(\\[\\{[^}]+\\}\\])(?!\\()" );

    public MarkdownParser( final Context context, final Reader in ) {
        super( context, in );
        if( context.getEngine().getManager( UserManager.class ).getUserDatabase() == null ||
            context.getEngine().getManager( AuthorizationManager.class ) == null ) {
            disableAccessRules();
        }
        context.getPage().setHasMetadata();
        parser = Parser.builder( MarkdownDocument.options( context, isImageInlining(), getInlineImagePatterns() ) ).build();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public WikiDocument parse() throws IOException {
        final String rawText = readFully( in );
        final ParsedPage parsed = FrontmatterParser.parse( rawText );

        for ( final Map.Entry< String, Object > entry : parsed.metadata().entrySet() ) {
            context.getPage().setAttribute( entry.getKey(), entry.getValue() );
        }

        // Normalize [{...}] wiki-syntax references by appending () so Flexmark parses them as inline links
        final String body = WIKI_BRACKET_REF.matcher( parsed.body() ).replaceAll( "$1()" );

        // Collect links from a clean AST (before JSPWiki post-processors modify URLs)
        collectLinks( body );

        final Node document = parser.parseReader( new BufferedReader( new StringReader( body ) ) );
        final MarkdownDocument md = new MarkdownDocument( context.getPage(), document );
        md.setContext( context );

        return md;
    }

    /**
     * Parses the body with a bare Flexmark parser (no JSPWiki extensions) and walks
     * the AST to find Link nodes, reporting them to the inherited mutator chains
     * so that ReferenceManager automatically tracks links in Markdown pages.
     */
    private void collectLinks( final String body ) {
        final Node root = LINK_SCANNER.parse( body );

        new NodeVisitor( new VisitHandler<>( Link.class, link -> {
            String url = link.getUrl().toString();
            if( url.isEmpty() ) {
                // Empty URL means wiki-style link: [PageName]() — text IS the page name
                final String text = link.getText().toString();
                if( !text.isEmpty() && !text.startsWith( "{" ) ) {
                    final int hash = text.indexOf( '#' );
                    final String pageName = hash >= 0 ? text.substring( 0, hash ) : text;
                    if( !pageName.isEmpty() ) {
                        callMutatorChain( localLinkMutatorChain, pageName );
                    }
                }
                return;
            }

            if( linkParsingOperations.isExternalLink( url ) ) {
                callMutatorChain( externalLinkMutatorChain, url );
            } else if( url.startsWith( "#" ) ) {
                // Anchor/footnote — not a page reference
            } else {
                final String attachment = context.getEngine()
                        .getManager( AttachmentManager.class )
                        .getAttachmentInfoName( context, url );
                if( attachment != null ) {
                    callMutatorChain( attachmentLinkMutatorChain, attachment );
                } else {
                    // Local wiki link — strip optional #section
                    final int hash = url.indexOf( '#' );
                    final String pageName = hash >= 0 ? url.substring( 0, hash ) : url;
                    if( !pageName.isEmpty() ) {
                        callMutatorChain( localLinkMutatorChain, pageName );
                    }
                }
            }
        } ) ).visit( root );
    }

    private static String readFully( final Reader reader ) throws IOException {
        final StringBuilder sb = new StringBuilder();
        final char[] buf = new char[ 4096 ];
        int n;
        while ( ( n = reader.read( buf ) ) != -1 ) {
            sb.append( buf, 0, n );
        }
        return sb.toString();
    }

}

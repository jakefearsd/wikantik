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
package com.wikantik.extractcli;

import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
import com.wikantik.auth.acl.AccessRules;
import com.wikantik.parser.markdown.RenderPathAccessRules;
import com.wikantik.util.AclDirectiveScanner;
import com.wikantik.util.OffsetRanges;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 *  Audits one page text for #104: what the single access-rule reader ({@link AccessRules#read}) makes of it, next to
 *  what each of the two readers it replaced enforced.
 *
 *  <p>The old text scan is {@link AclDirectiveScanner#findAll} (ALLOW only) minus spans inside Markdown code, the code
 *  located by a vanilla CommonMark parse of the whole text — the reading {@code EnforcedAclDirectives} (package-private
 *  in wikantik-main, kept there for hiding) still implements; this is a CLI-only copy of its few lines. The old render
 *  path is {@link RenderPathAccessRules#find}, still public production code, whose rule lines the old
 *  {@code parseAcl} read by skipping the first word whatever it was (so a {@code DENY} was a grant). Nothing in the
 *  production tree is changed or widened for the audit.</p>
 *
 *  <p>An "ACL" here is the set of {@code permission:principal} grants a reading yields, sorted; {@code LOCKED} for a
 *  locked page, {@code (none)} for no grant, {@code UNREADABLE} for the old render path on a text it could not parse.
 *  Principals are compared as written (not resolved against users or groups).</p>
 */
final class AclShapeAudit {

    private static final Logger LOG = LogManager.getLogger( AclShapeAudit.class );

    static final String NONE = "(none)";
    static final String LOCKED = "LOCKED";
    static final String UNREADABLE = "UNREADABLE";

    private static final Set< String > PERMISSIONS =
            Set.of( "view", "comment", "edit", "modify", "upload", "rename", "delete" );
    private static final Parser CODE_PARSER = Parser.builder().build();

    private AclShapeAudit() {
    }

    /**
     *  @param name         page name
     *  @param locked       the reader locks the page
     *  @param reasons      one line per disputed span when locked (shape, line or frontmatter field, text)
     *  @param escaped      the text holds an escaped directive the reader treats as literal (widens vs the old text scan)
     *  @param oldTextScan  the ACL the old text scan enforced
     *  @param oldRender    the ACL the old render path enforced
     *  @param newAcl       the ACL the reader gives
     *  @param changed      the new ACL differs from either old ACL
     */
    record PageAudit( String name, boolean locked, List< String > reasons, boolean escaped, String oldTextScan,
                      String oldRender, String newAcl, boolean changed ) {

        String reading() {
            return locked ? "Locked" : "Rules";
        }
    }

    static PageAudit audit( final String name, final String text ) {
        final String body = text == null ? "" : text;
        final AccessRules.Reading reading = AccessRules.read( body );
        final List< String > reasons = new ArrayList<>();
        final String newAcl;
        if ( reading instanceof AccessRules.Locked l ) {
            for ( final AccessRules.Reason r : l.reasons() ) {
                reasons.add( r.shape() + ( r.line() != null ? " line " + r.line() : "" )
                        + ( r.field() != null ? " field " + r.field() : "" ) + ": " + r.text() );
            }
            newAcl = LOCKED;
        } else {
            newAcl = acl( ( ( AccessRules.Rules ) reading ).ruleLines() );
        }
        final boolean escaped = reading.spans().stream().anyMatch( s -> s.shape() == AccessRules.Shape.ESCAPED );
        final String oldText = acl( oldTextScanLines( body ) );
        final String oldRender = oldRenderAcl( body );
        return new PageAudit( name, reading instanceof AccessRules.Locked, reasons, escaped, oldText, oldRender, newAcl,
                !newAcl.equals( oldText ) || !newAcl.equals( oldRender ) );
    }

    private static String oldRenderAcl( final String text ) {
        try {
            return acl( RenderPathAccessRules.find( text ).stream().map( RenderPathAccessRules.Rule::ruleLine ).toList() );
        } catch ( final RenderPathAccessRules.Unreadable e ) {
            LOG.debug( "AclShapeAudit: the old render reading cannot read a {}-char text: {}", text.length(), e.getMessage() );
            return UNREADABLE;
        }
    }

    private static List< String > oldTextScanLines( final String text ) {
        final List< String > lines = new ArrayList<>();
        for ( final AclDirectiveScanner.Span s : oldTextScanSpans( text ) ) {
            lines.add( s.keyword() + " " + s.permission() + " " + text.substring( s.principalsStart(), s.principalsEnd() ) );
        }
        return lines;
    }

    /** The raw directive text the old text scan enforced; compared with {@code EnforcedAclDirectives.directives} by a drift test. */
    static List< String > oldTextScanDirectives( final String text ) {
        return oldTextScanSpans( text ).stream().map( s -> text.substring( s.start(), s.end() ) ).toList();
    }

    private static List< AclDirectiveScanner.Span > oldTextScanSpans( final String text ) {
        final List< AclDirectiveScanner.Span > candidates = AclDirectiveScanner.findAll( text, false );
        if ( candidates.isEmpty() ) {
            return List.of();
        }
        final OffsetRanges code = OffsetRanges.of( codeRegions( text ) );
        final List< AclDirectiveScanner.Span > kept = new ArrayList<>();
        for ( final AclDirectiveScanner.Span s : candidates ) {
            if ( !code.contains( s.start() ) ) {
                kept.add( s );
            }
        }
        return kept;
    }

    private static List< int[] > codeRegions( final String text ) {
        final List< int[] > ranges = new ArrayList<>();
        final Document doc = CODE_PARSER.parse( text );
        new NodeVisitor(
            new VisitHandler<>( Code.class, n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) ),
            new VisitHandler<>( FencedCodeBlock.class, n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) ),
            new VisitHandler<>( IndentedCodeBlock.class, n -> ranges.add( new int[]{ n.getStartOffset(), n.getEndOffset() } ) )
        ).visit( doc );
        return ranges;
    }

    /** The sorted {@code permission:principal} grants of rule lines ({@code KEYWORD actions principals}); a line the old parser refused grants nothing. */
    static String acl( final List< String > ruleLines ) {
        final Set< String > grants = new TreeSet<>();
        for ( final String line : ruleLines ) {
            final String[] parts = line.trim().split( "\\s+", 3 );
            if ( parts.length < 3 ) {
                continue;
            }
            final List< String > actions = new ArrayList<>();
            boolean valid = true;
            for ( final String action : parts[ 1 ].split( "," ) ) {
                final String a = action.trim().toLowerCase( Locale.ROOT );
                valid &= PERMISSIONS.contains( a );
                actions.add( a );
            }
            if ( !valid ) {
                continue;
            }
            for ( final String principal : parts[ 2 ].split( "," ) ) {
                final String p = principal.trim().replaceAll( "\\s+", " " );
                if ( !p.isEmpty() ) {
                    actions.forEach( a -> grants.add( a + ":" + p ) );
                }
            }
        }
        return grants.isEmpty() ? NONE : String.join( "; ", grants );
    }
}

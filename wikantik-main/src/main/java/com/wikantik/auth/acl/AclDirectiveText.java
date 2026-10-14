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
package com.wikantik.auth.acl;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.parser.markdown.RenderPathAccessRules;
import com.wikantik.util.AclDirectiveScanner;
import com.wikantik.util.AclDirectives;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Removes access-rule directives ({@code [{ALLOW edit alice@example.test}]}) from page text shown to someone who may not
 * edit the page — raw page reads, diffs, exports, agent context, search snippets, retrieval chunks. Directives name
 * people by login, so they must not leave the building through any of those.
 *
 * <p><b>The guarantee, by construction.</b> Whatever {@link DefaultAclManager} <em>enforces</em> in a text — every
 * {@code [{ALLOW …}]} that does not start inside Markdown code, as judged by the same scanner and the same Markdown
 * parser the ACL manager uses, and every rule the render path reads ({@code RenderPathAccessRules}: wrapped over
 * lines, {@code DENY}, …) — is removed. Each round first removes exactly those spans ({@link EnforcedAclDirectives}),
 * then runs the conservative {@link AclDirectives} pass (which also removes {@code DENY} and hides a documentation
 * example it cannot be sure is code), and the loop only ends on a round that changes nothing: so in the result the ACL
 * manager enforces nothing, and {@code DefaultAclManager.aclDirectives( result )} is empty. {@link AclDirectives} alone
 * cannot promise that — its small code scanner and a real Markdown parser disagree in places — which is why every
 * caller uses this class. A documentation example in genuine code (<code>`[{ALLOW edit Bob}]`</code>, a closed fence)
 * is kept whenever both agree it is code. Display rendering parses the text this class returns, so no rendered page
 * shows an enforced rule either.
 *
 * <p><b>Frontmatter stays whole.</b> The frontmatter and the body are handled apart: the util pass reads frontmatter in
 * one-line mode (the renderer never reads it, so the wrapped shape does not apply there and a stray opener in a value
 * cannot run to a closer in the body), the closing delimiter is never removed, and a removal that would splice an
 * earlier closing delimiter drops the frontmatter rather than letting its remaining lines be read as body.
 *
 * <p>Removing a span can splice the text either side into a new directive, so rounds repeat. The loop is bounded
 * ({@value #MAX_ROUNDS}); text that is still reshaping after that is adversarial, and fails <em>closed</em>: every
 * directive-shaped span is removed, code or not, up to {@value #DRASTIC_ROUNDS} more times, and if the text still keeps
 * re-forming one the result is empty. The length of a directive is never bounded — a bound would silently stop
 * enforcing (and hiding) a long access list.
 *
 * <p>Everything else is returned byte for byte, and the very same instance when there is nothing to remove. A text with
 * no directive-shaped span is never even parsed as Markdown.
 */
public final class AclDirectiveText {

    private static final Logger LOG = LogManager.getLogger( AclDirectiveText.class );

    /** Rounds of "remove what is enforced, then run the util pass" before the fail-closed fallback. */
    static final int MAX_ROUNDS = 8;

    /** Rounds of removing every directive-shaped span, code or not, after {@link #MAX_ROUNDS}. */
    static final int DRASTIC_ROUNDS = 64;

    /** What a reader gets in place of a frontmatter block that removing a directive would cut short. */
    private static final String EMPTY_FRONTMATTER = "---\n---\n";

    /** The frontmatter delimiter line's text. */
    private static final String DELIMITER = "---";

    private AclDirectiveText() {
    }

    /**
     * @param text raw page text, may be {@code null}
     * @return the text without the access-rule directives it enforces (and the ones the util pass removes); the same
     *         instance when there is nothing to remove; {@code null} for {@code null}
     */
    public static String strip( final String text ) {
        return strip( text, "" );
    }

    /**
     * Like {@link #strip(String)}, but each removed directive is replaced by {@code replacement} (for callers, such as
     * excerpt builders, that keep the words either side of it apart).
     *
     * @param text        raw page text, may be {@code null}
     * @param replacement put in place of each directive; must not itself contain an opening <code>[&#123;</code>
     * @return the text with directives replaced; the same instance when there were none
     */
    public static String strip( final String text, final String replacement ) {
        Objects.requireNonNull( replacement, "replacement" );
        if ( text == null || text.indexOf( "[{" ) < 0 && !RenderPathAccessRules.mayHoldRule( text ) ) {
            return text;
        }
        String current = text;
        for ( int round = 0; round < MAX_ROUNDS; round++ ) {
            final String next = oneRound( current, replacement );
            if ( next.equals( current ) ) {
                return current;
            }
            current = next;
        }
        return failClosed( current, replacement );
    }

    /**
     * The one place the rule "whoever may edit the page reads it verbatim — the editor round-trips that text — everyone
     * else reads it without the access rules" is written down.
     *
     * @param canEdit whether the caller holds {@code edit} on the page the text belongs to
     * @param text    raw page text, may be {@code null}
     * @return {@code text} itself for an editor, {@link #strip(String)} of it for everyone else
     */
    public static String forViewer( final boolean canEdit, final String text ) {
        return canEdit ? text : strip( text );
    }

    /**
     * The frontmatter and body of {@link #strip(String) stripped} <em>full</em> page text — the way to hand a body to
     * anything that works on a body only (the chunker, summary and excerpt builders, the agent projection).
     *
     * <p>Strip the whole text, never the body alone: {@code DefaultAclManager} enforces on the full text, frontmatter
     * included, and frontmatter can change which body regions are code (a YAML block scalar that opens a fence, or a
     * {@code <!--} that runs past the closing {@code ---}). A rule enforced in the full text can look like
     * documentation in the body on its own, and be kept.
     *
     * @param fullText raw page text including frontmatter, may be {@code null} (read as empty)
     * @return the parsed stripped text; never {@code null}
     */
    public static ParsedPage parseStripped( final String fullText ) {
        return parseStripped( fullText, "" );
    }

    /**
     * Like {@link #parseStripped(String)}, each removed directive replaced by {@code replacement} — for the excerpt and
     * summary builders, which keep the words either side of an inline directive apart with {@code " "}.
     *
     * @param fullText    raw page text including frontmatter, may be {@code null} (read as empty)
     * @param replacement put in place of each directive (see {@link #strip(String, String)})
     * @return the parsed stripped text; never {@code null}
     */
    public static ParsedPage parseStripped( final String fullText, final String replacement ) {
        return FrontmatterParser.parse( strip( fullText == null ? "" : fullText, replacement ) );
    }

    /** Removes what the ACL manager enforces, then runs the conservative util pass. */
    private static String oneRound( final String text, final String replacement ) {
        return without( text, EnforcedAclDirectives.removalRanges( text ), replacement, true );
    }

    /**
     * {@code text} less {@code ranges} (each replaced by {@code replacement}), then, when {@code utilPass}, the util pass.
     * Frontmatter and body are handled apart:
     * <ul>
     *   <li>the util pass reads the frontmatter in one-line mode: the renderer never reads frontmatter, so the wrapped
     *       shape does not apply there, and a stray opener in a value can never run to a closer in the body;</li>
     *   <li>the closing delimiter is never removed, not even by a range that spans it (a one-line rule can, through its
     *       whitespace runs: <code>[{ALLOW edit\n---\n}]</code>) — the parts either side of it go;</li>
     *   <li>if a removal in the frontmatter splices an earlier closing delimiter, the frontmatter would end early and its
     *       remaining lines would be read as body, shown to readers: the frontmatter is dropped instead.</li>
     * </ul>
     */
    private static String without( final String text, final List< int[] > ranges, final String replacement,
                                   final boolean utilPass ) {
        final int bodyStart = FrontmatterParser.bodyOffset( text );
        if ( bodyStart == 0 ) {
            final String out = remove( text, ranges, 0, text.length(), replacement );
            return utilPass ? AclDirectives.strip( out, replacement, true ) : out;
        }
        final int closeStart = closingDelimiterStart( text, bodyStart );
        String head = remove( text, ranges, 0, closeStart, replacement );
        String body = remove( text, ranges, bodyStart, text.length(), replacement );
        if ( utilPass ) {
            head = AclDirectives.strip( head, replacement, false );
            body = AclDirectives.strip( body, replacement, true );
        }
        final String result = head + text.substring( closeStart, bodyStart ) + body;
        if ( FrontmatterParser.bodyOffset( result ) != result.length() - body.length() ) {
            LOG.warn( "Removing access-rule directives from the frontmatter of a text of {} characters would end it early; "
                    + "readers get the text without its frontmatter", text.length() );
            return EMPTY_FRONTMATTER + body;
        }
        return result;
    }

    /** Where the closing {@code ---} line (with the line end before it, if any) starts; {@code bodyStart} follows it. */
    static int closingDelimiterStart( final String text, final int bodyStart ) {
        return lineEndBefore( text, lineEndBefore( text, bodyStart ) - DELIMITER.length() );
    }

    private static int lineEndBefore( final String text, final int at ) {
        int i = at;
        if ( i > 0 && text.charAt( i - 1 ) == '\n' ) {
            i--;
        }
        if ( i > 0 && text.charAt( i - 1 ) == '\r' ) {
            i--;
        }
        return i;
    }

    /**
     * The bound was hit, so the text keeps re-forming directives. Remove every directive-shaped span — code or not — and
     * look again; if even that does not settle, return nothing.
     */
    private static String failClosed( final String text, final String replacement ) {
        LOG.warn( "Access-rule directive stripping did not settle after {} rounds on a text of {} characters; "
                + "removing every directive-shaped span, code or not", MAX_ROUNDS, text.length() );
        String current = text;
        for ( int round = 0; round < DRASTIC_ROUNDS; round++ ) {
            final List< int[] > any = anyDirective( current );
            if ( any.isEmpty() ) {
                return current;
            }
            current = without( current, any, replacement, false );
        }
        LOG.warn( "Access-rule directive stripping still found directives after {} more rounds on a text of {} characters; "
                + "returning an empty text", DRASTIC_ROUNDS, text.length() );
        return "";
    }

    /**
     * Every directive-shaped span, code or not, each with an empty {@code ()} after it: one-line {@code ALLOW}/{@code DENY}
     * spans anywhere (a superset of the text scan), wrapped ones in the body, and the render path's rules. When none is
     * left, nothing is enforced.
     */
    private static List< int[] > anyDirective( final String text ) {
        final List< int[] > ranges = new ArrayList<>();
        for ( final AclDirectiveScanner.Span span : AclDirectiveScanner.findAll( text, true, false ) ) {
            ranges.add( new int[]{ span.start(), AclDirectiveScanner.endWithEmptyParentheses( text, span ) } );
        }
        final int bodyStart = FrontmatterParser.bodyOffset( text );
        final String body = text.substring( bodyStart );
        for ( final AclDirectiveScanner.Span span : AclDirectiveScanner.findAll( body, true, true ) ) {
            ranges.add( new int[]{ bodyStart + span.start(), bodyStart + AclDirectiveScanner.endWithEmptyParentheses( body, span ) } );
        }
        for ( final RenderPathAccessRules.Rule rule : RenderPathAccessRules.find( text ) ) {
            ranges.add( new int[]{ rule.start(), rule.end() } );
        }
        return EnforcedAclDirectives.merged( ranges );
    }

    /**
     * {@code text[from, to)} with the part of each range inside it replaced: by {@code replacement} where the range
     * starts, by nothing for the tail of a range that began before {@code from}. Ranges are in order and disjoint.
     */
    private static String remove( final String text, final List< int[] > ranges, final int from, final int to,
                                  final String replacement ) {
        final StringBuilder out = new StringBuilder( to - from );
        int copiedTo = from;
        for ( final int[] range : ranges ) {
            final int start = Math.max( range[ 0 ], from );
            final int end = Math.min( range[ 1 ], to );
            if ( start < end ) {
                out.append( text, copiedTo, start ).append( range[ 0 ] >= from ? replacement : "" );
                copiedTo = end;
            }
        }
        return out.append( text, copiedTo, to ).toString();
    }
}

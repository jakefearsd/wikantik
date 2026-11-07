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
import com.wikantik.util.TrackedText;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Removes access-rule directives ({@code [{ALLOW edit alice@example.test}]}) from page text shown to someone who may not
 * edit the page — raw page reads, diffs, exports, agent context, search snippets, retrieval chunks, and (through display
 * rendering) every rendered surface. Directives name people by login, so they must not leave the building.
 *
 * <p><b>The guarantee, by construction.</b> In the result, no reading of access rules finds one: not the access-rule
 * reader ({@link AccessRules}: what the wiki enforces — hidden ⊇ enforced), not the old text scan
 * ({@link EnforcedAclDirectives}: single-line {@code [{ALLOW …}]} outside Markdown code) and not the render path
 * ({@link RenderPathAccessRules}: what rendering reads as a rule — rules wrapped over lines, {@code DENY}, any permission
 * case, {@code ALLOWED}, …). Each round removes exactly the spans they find, then runs the conservative
 * {@link AclDirectives} pass, and the loop only ends on a round that changes nothing, so none of them finds anything in
 * what is returned. A documentation example in genuine code (<code>`[{ALLOW edit Bob}]`</code>, a closed fence) is kept
 * whenever all agree it is code.
 *
 * <p><b>Fail closed.</b> When the render path's reading cannot be had ({@link RenderPathAccessRules.Unreadable}: a
 * parse that throws, a rule with no source span, a page built to blow up the parse) or the rounds do not settle, every
 * directive-shaped span is removed, code or not, whatever the case of its keyword or permission and, in the body, wrapped
 * or not; if anything either reading could still take for a rule is left, the result is empty.
 *
 * <p><b>Layout stays.</b> A directive that is all its line holds goes with that line's terminator, so no blank line is
 * left to end the paragraph or HTML block it sat in — except where that would make the text, or the body after its
 * frontmatter, start a frontmatter block it did not start: then a line end stays in front of the {@code ---}, so stripping
 * never makes up metadata. The frontmatter and the body are handled apart: the util pass reads
 * frontmatter in one-line mode (the renderer never reads it, so the wrapped shape does not apply there and a stray opener
 * in a value cannot run to a closer in the body), the closing delimiter is never removed, and a removal that would splice
 * an earlier closing delimiter drops the frontmatter rather than letting its remaining lines be read as body.
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
        if ( text == null ) {
            return null;
        }
        return stripTracked( text, replacement ).text();
    }

    /**
     * {@link #strip(String)}, tracked: the very same text, together with where each of its characters came from in
     * {@code text}. Stripping only cuts (a directive, or the line it was all of) — the one exception is a line end or an
     * empty frontmatter block it writes itself to keep a frontmatter block from appearing or being cut short, and those
     * have no source of their own. Block numbering parses this text, as a render does, and maps what it finds back to
     * the raw text through it.
     *
     * @param text raw page text, may be {@code null}
     * @return the stripped text tracked back to {@code text}; {@code null} for {@code null}
     */
    public static TrackedText stripTracked( final String text ) {
        return text == null ? null : stripTracked( text, "" );
    }

    private static TrackedText stripTracked( final String text, final String replacement ) {
        final TrackedText source = TrackedText.of( text );
        if ( text.indexOf( "[{" ) < 0 && !RenderPathAccessRules.mayHoldRule( text ) ) {
            return source;
        }
        try {
            return toFixedPoint( source, replacement );
        } catch ( final RenderPathAccessRules.Unreadable e ) {
            LOG.warn( "The access rules in a text of {} characters could not be read the way the renderer reads them ({}); "
                    + "removing every directive-shaped span, code or not", text.length(), e.getMessage() );
            return failClosed( source, replacement );
        }
    }

    private static TrackedText toFixedPoint( final TrackedText text, final String replacement ) {
        TrackedText current = text;
        for ( int round = 0; round < MAX_ROUNDS; round++ ) {
            final TrackedText next = oneRound( current, replacement );
            if ( next.text().equals( current.text() ) ) {
                return current;
            }
            current = next;
        }
        LOG.warn( "Access-rule directive stripping did not settle after {} rounds on a text of {} characters; "
                + "removing every directive-shaped span, code or not", MAX_ROUNDS, text.length() );
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

    /**
     * Removes what the old text scan read, what the render path reads, and every rule the access-rule reader reads (what
     * the wiki enforces, a lower-case or spaced rule neither old reading read included), then runs the conservative util
     * pass. Only the reader's rules (and the wrapped rules it locks for a line break inside a name, which it reads from
     * one label just as it reads a rule): its other spans run from each opener to the first closer, so where openers nest
     * they overlap, and cutting the widest at once would leave the tail of a rule the next round would have reformed and
     * cut.
     */
    private static TrackedText oneRound( final TrackedText tracked, final String replacement ) {
        final String text = tracked.text();
        final List< int[] > ranges = new ArrayList<>();
        for ( final AclDirectiveScanner.Span span : EnforcedAclDirectives.spans( text ) ) {
            ranges.add( new int[]{ span.start(), AclDirectiveScanner.endWithEmptyParentheses( text, span ) } );
        }
        // One render parse gives both the render path's rules and the access-rule reader's.
        final AccessRules.Parsed parsed = AccessRules.parse( text );
        if ( parsed.unreadable() != null ) {
            throw parsed.unreadable();
        }
        for ( final RenderPathAccessRules.Rule rule : parsed.renderRules() ) {
            ranges.add( new int[]{ rule.start(), rule.end() } );
        }
        for ( final AccessRules.Span span : parsed.reading().spans() ) {
            if ( AccessRules.isRule( span.shape() ) || span.shape() == AccessRules.Shape.WRAPPED_NAME ) {
                ranges.add( new int[]{ span.start(), span.end() } );
            }
        }
        ranges.addAll( parsed.inMath() );   // math source is shown: a directive there is hidden like one in prose
        return without( tracked, merged( text, ranges ), replacement, true );
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
    private static TrackedText without( final TrackedText tracked, final List< int[] > ranges, final String replacement,
                                        final boolean utilPass ) {
        final String text = tracked.text();
        final int bodyStart = FrontmatterParser.bodyOffset( text );
        if ( bodyStart == 0 ) {
            final TrackedText out = remove( tracked, ranges, 0, text.length(), replacement );
            return noNewFrontmatter( text, 0, utilPass ? AclDirectives.strip( out, replacement, true ) : out );
        }
        final int closeStart = closingDelimiterStart( text, bodyStart );
        TrackedText head = remove( tracked, ranges, 0, closeStart, replacement );
        TrackedText body = remove( tracked, ranges, bodyStart, text.length(), replacement );
        if ( utilPass ) {
            head = AclDirectives.strip( head, replacement, false );
            body = AclDirectives.strip( body, replacement, true );
        }
        body = noNewFrontmatter( text, bodyStart, body );
        final TrackedText result = TrackedText.concat( head, tracked.builder( closeStart ).copy( closeStart, bodyStart ).build(), body );
        if ( FrontmatterParser.bodyOffset( result.text() ) != result.length() - body.length() ) {
            LOG.warn( "Removing access-rule directives from the frontmatter of a text of {} characters would end it early; "
                    + "readers get the text without its frontmatter", text.length() );
            return TrackedText.concat( TrackedText.literal( EMPTY_FRONTMATTER, tracked, tracked.toSourceEnd( 0 ) ), body );
        }
        return result;
    }

    /**
     * {@code out}, the region of {@code text} from {@code regionStart} with directives removed — or, when that removal made
     * the region start a frontmatter block it did not start (a removed line above a {@code ---} line), {@code out} after
     * the delimiter's own line end: the removed line's end stays, so the first {@code ---} is no longer the region's
     * first line, and no reader is handed metadata (or loses paragraphs to it) that the page never had. Applies to the
     * text and to the body after its frontmatter, which an embed renders on its own.
     */
    private static TrackedText noNewFrontmatter( final String text, final int regionStart, final TrackedText out ) {
        if ( FrontmatterParser.bodyOffset( out.text() ) == 0 || FrontmatterParser.bodyOffset( text.substring( regionStart ) ) > 0 ) {
            return out;
        }
        final String lineEnd = out.text().startsWith( DELIMITER + "\r\n" ) ? "\r\n" : "\n";
        return TrackedText.concat( TrackedText.literal( lineEnd, out, out.toSourceEnd( 0 ) ), out );
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
     * Removes every directive-shaped span ({@link DirectiveShapes#directiveShaped}), code or not, until none is left; if either reading
     * could still take anything for a rule, returns nothing.
     */
    static String failClosed( final String text, final String replacement ) {
        return failClosed( TrackedText.of( text ), replacement ).text();
    }

    /** {@link #failClosed(String, String)}, tracked back to {@code text}'s source. */
    static TrackedText failClosed( final TrackedText text, final String replacement ) {
        TrackedText current = text;
        for ( int round = 0; round < DRASTIC_ROUNDS; round++ ) {
            final List< int[] > any = DirectiveShapes.directiveShaped( current.text() );
            if ( any.isEmpty() ) {
                break;
            }
            current = without( current, any, replacement, false );
        }
        if ( DirectiveShapes.stillReadable( current.text() ) ) {
            LOG.warn( "Access-rule directive stripping still found directive-shaped text in a text of {} characters; "
                    + "returning an empty text", text.length() );
            return text.builder().build();
        }
        return current;
    }

    /**
     * {@code ranges} sorted by start; overlapping ones joined, and so are ones with nothing but spaces and tabs between
     * them (two rules that are all their line holds then go with the line, see {@link AclDirectives#removal}).
     */
    static List< int[] > merged( final String text, final List< int[] > ranges ) {
        if ( ranges.size() < 2 ) {
            return ranges;
        }
        ranges.sort( Comparator.comparingInt( r -> r[ 0 ] ) );
        final List< int[] > out = new ArrayList<>( ranges.size() );
        int[] current = ranges.get( 0 ).clone();
        for ( int i = 1; i < ranges.size(); i++ ) {
            final int[] next = ranges.get( i );
            if ( next[ 0 ] <= current[ 1 ] || onlyBlanks( text, current[ 1 ], next[ 0 ] ) ) {
                current[ 1 ] = Math.max( current[ 1 ], next[ 1 ] );
            } else {
                out.add( current );
                current = next.clone();
            }
        }
        out.add( current );
        return out;
    }

    private static boolean onlyBlanks( final String text, final int from, final int to ) {
        for ( int i = from; i < to; i++ ) {
            if ( text.charAt( i ) != ' ' && text.charAt( i ) != '\t' ) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code text[from, to)} with the part of each range inside it replaced: by {@code replacement} where the range
     * starts (by nothing when the range is all its line holds: the line goes, see {@link AclDirectives#removal}), by
     * nothing for the tail of a range that began before {@code from}. Ranges are in order and disjoint.
     */
    private static TrackedText remove( final TrackedText tracked, final List< int[] > ranges, final int from, final int to,
                                       final String replacement ) {
        final String text = tracked.text();
        final TrackedText.Builder out = tracked.builder( from );
        int copiedTo = from;
        for ( final int[] range : ranges ) {
            final int start = Math.max( range[ 0 ], copiedTo );
            final int end = Math.min( range[ 1 ], to );
            if ( start >= end ) {
                continue;
            }
            if ( range[ 0 ] < from ) {          // the tail of a range that began before this region: its start was replaced there
                copiedTo = end;
                continue;
            }
            final AclDirectives.Removal cut = AclDirectives.removal( text, start, end, copiedTo, to );
            out.copy( copiedTo, cut.start() ).insert( cut.wholeLine() ? "" : replacement );
            copiedTo = cut.end();
        }
        return out.copy( copiedTo, to ).build();
    }
}

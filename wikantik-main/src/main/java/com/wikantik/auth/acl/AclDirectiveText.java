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

import com.wikantik.util.AclDirectiveScanner;
import com.wikantik.util.AclDirectives;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;

/**
 * Removes access-rule directives ({@code [{ALLOW edit alice@example.test}]}) from page text shown to someone who may not
 * edit the page — raw page reads, diffs, exports, agent context, search snippets, retrieval chunks. Directives name
 * people by login, so they must not leave the building through any of those.
 *
 * <p><b>The guarantee, by construction.</b> Whatever {@link DefaultAclManager} <em>enforces</em> in a text — every
 * {@code [{ALLOW …}]} that does not start inside Markdown code, as judged by the same scanner and the same Markdown
 * parser the ACL manager uses — is removed. Each round first removes exactly those spans, then runs the conservative
 * {@link AclDirectives} pass (which also removes {@code DENY} and hides a documentation example it cannot be sure is
 * code), and the loop only ends on a round that changes nothing: so in the result the ACL manager enforces nothing,
 * and {@code DefaultAclManager.aclDirectives( result )} is empty. {@link AclDirectives} alone cannot promise that — its
 * small code scanner and a real Markdown parser disagree in places — which is why every caller uses this class.
 * A documentation example in genuine code (<code>`[{ALLOW edit Bob}]`</code>, a closed fence) is kept whenever both
 * agree it is code.
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
        if ( text == null || text.indexOf( "[{" ) < 0 ) {
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

    /** Removes what the ACL manager enforces, then runs the conservative util pass. */
    private static String oneRound( final String text, final String replacement ) {
        final List< AclDirectiveScanner.Span > enforced = EnforcedAclDirectives.spans( text );
        final String withoutEnforced = enforced.isEmpty() ? text : remove( text, enforced, replacement );
        return AclDirectives.strip( withoutEnforced, replacement );
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
            final List< AclDirectiveScanner.Span > any = AclDirectiveScanner.findAll( current, true );
            if ( any.isEmpty() ) {
                return current;
            }
            current = remove( current, any, replacement );
        }
        LOG.warn( "Access-rule directive stripping still found directives after {} more rounds on a text of {} characters; "
                + "returning an empty text", DRASTIC_ROUNDS, text.length() );
        return "";
    }

    /** {@code text} with each span (and an empty {@code ()} right after it) replaced; spans are in order and disjoint. */
    private static String remove( final String text, final List< AclDirectiveScanner.Span > spans, final String replacement ) {
        final StringBuilder out = new StringBuilder( text.length() );
        int copiedTo = 0;
        for ( final AclDirectiveScanner.Span span : spans ) {
            out.append( text, copiedTo, span.start() ).append( replacement );
            copiedTo = AclDirectiveScanner.endWithEmptyParentheses( text, span );
        }
        return out.append( text, copiedTo, text.length() ).toString();
    }
}

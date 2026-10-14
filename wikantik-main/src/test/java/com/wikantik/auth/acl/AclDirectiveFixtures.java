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

import java.util.Set;

/**
 * Page texts that carry access rules in the shapes the text scan and the render path disagree on. Each fixture records
 * the ACL the ACL manager's text scan enforces on it (as {@code principal:action} pairs) — unchanged from 2.4.57, see
 * {@code AclEnforcementUnchangedTest} — and whether its login must be hidden from readers: it must whenever either the
 * text scan or the render path reads a rule naming it. Shared by the enforcement, stripping and rendering tests so each
 * shape is checked on every surface.
 */
public final class AclDirectiveFixtures {

    /** A login that is an email address, the worst case: it must reach no reader. */
    public static final String LOGIN = "alice@example.test";

    /**
     * One fixture.
     *
     * @param name        label
     * @param text        the page text
     * @param acl         what the ACL manager's text scan enforces (2.4.57 behaviour)
     * @param loginHidden whether the text scan or the render path reads a rule here, so readers must not see it
     */
    public record Fixture( String name, String text, Set< String > acl, boolean loginHidden ) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Prose every fixture ends with, so a reader's output can be checked for the page itself. */
    public static final String BODY = "The visible body text.";

    /** Two links, one per line: both the text scan and the render path read them. */
    public static final Fixture PLAIN = new Fixture( "plain",
            "[{ALLOW view All}]\n[{ALLOW edit Admin," + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of( "All:view", "Admin:edit", LOGIN + ":edit" ), true );

    /** Inside an HTML block the fence is plain text: the text scan enforces the rule, the renderer prints it. */
    public static final Fixture HTML_BLOCK = new Fixture( "html block",
            "[{ALLOW view All}]\n\n<details>\n```\n[{ALLOW edit Admin," + LOGIN + "}]\n```\n</details>\n\n" + BODY + "\n",
            Set.of( "All:view", "Admin:edit", LOGIN + ":edit" ), true );

    /** A link label may run over several lines (no blank line): the render path reads the rule, the one-line scan does not. */
    public static final Fixture WRAPPED = new Fixture( "wrapped",
            "[{ALLOW view All}]\n[{ALLOW edit Admin,\n " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of( "All:view" ), true );

    /** Wrapped inside a blockquote: the renderer reads the label without the continuation line's {@code >}. */
    public static final Fixture QUOTED_WRAPPED = new Fixture( "wrapped in a blockquote",
            "> [{ALLOW edit Admin,\n> " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of(), true );

    /** The render path reads {@code DENY} as a grant (its parser skips the first word); the text scan ignores it. */
    public static final Fixture DENY = new Fixture( "deny",
            "[{ALLOW view All}]\n[{DENY edit " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of( "All:view" ), true );

    /** The permission is matched case-insensitively by the render path, case-sensitively by the text scan. */
    public static final Fixture UPPER_CASE = new Fixture( "upper-case permission",
            "[{ALLOW VIEW " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of(), true );

    /** {@code ALLOWED}: the render path only checks the prefix and skips the first word. */
    public static final Fixture KEYWORD_PREFIX = new Fixture( "keyword with a suffix",
            "[{ALLOWED edit " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of(), true );

    /** In inline math the renderer never sees a link, but the text scan (no math) enforces it. */
    public static final Fixture INLINE_MATH = new Fixture( "inline math",
            "Formula $[{ALLOW edit " + LOGIN + "}]$ here.\n\n" + BODY + "\n",
            Set.of( LOGIN + ":edit" ), true );

    /** In a code span: documentation for both. */
    public static final Fixture CODE_SPAN = new Fixture( "code span",
            "Write `[{ALLOW edit " + LOGIN + "}]` to restrict a page.\n\n" + BODY + "\n",
            Set.of(), false );

    /** A blank line ends a link label: nobody enforces this, however it looks. */
    public static final Fixture BLANK_LINE = new Fixture( "blank line inside",
            "Use [{ALLOW edit Admin,\n\n" + LOGIN + "}] like so.\n\n" + BODY + "\n",
            Set.of(), false );

    /**
     * An unterminated opener in a frontmatter value, a body closer in code: no directive at all. The renderer never
     * reads frontmatter, and the one-line scan cannot cross the line ends.
     */
    public static final Fixture FRONTMATTER_OPENER = new Fixture( "opener in frontmatter",
            "---\ntitle: Access rules\nverified_by: " + LOGIN + "\nsummary: How to use [{ALLOW view and [{DENY edit rules\n---\n"
                    + "Use `[{ALLOW edit Alice}]` to restrict a page.\n\n" + BODY + "\n",
            Set.of(), false );

    /** A long wrapped principal list: the renderer bounds a link label by no length, so neither may the stripping. */
    public static final Fixture LONG_WRAPPED = longWrapped();

    private static Fixture longWrapped() {
        final StringBuilder text = new StringBuilder( "[{ALLOW edit " );
        for ( int i = 0; i < 400; i++ ) {
            text.append( "user" ).append( i ).append( "@example.test,\n " );
        }
        text.append( LOGIN ).append( "}]\n\n" ).append( BODY ).append( "\n" );
        return new Fixture( "long wrapped list (" + text.length() + " chars)", text.toString(), Set.of(), true );
    }

    /**
     * A rule inside a {@code $$} block whose generated math fence an inner fence closes early: the renderer reads the
     * rule, so it must be hidden (round 1 mapped its span to nothing and kept it).
     */
    public static final Fixture MATH_FENCE = new Fixture( "rule in a $$ block broken by a fence",
            "[{ALLOW view All}]\n\n$$\n```\n[{ALLOW edit Admin,\n " + LOGIN + "}]\n$$\n```\n\n" + BODY + "\n",
            Set.of( "All:view" ), true );

    /** Every fixture. */
    public static final java.util.List< Fixture > ALL = java.util.List.of( PLAIN, HTML_BLOCK, WRAPPED, QUOTED_WRAPPED, DENY,
            UPPER_CASE, KEYWORD_PREFIX, INLINE_MATH, CODE_SPAN, BLANK_LINE, FRONTMATTER_OPENER, LONG_WRAPPED, MATH_FENCE );

    /**
     * The round-1 review's enforcement repros: each must enforce exactly what 2.4.57 enforced. Not rendered (the first
     * makes the renderer itself throw, which is pre-existing).
     */
    public static final java.util.List< Fixture > ENFORCEMENT_REPROS = java.util.List.of(
            new Fixture( "footnote that makes the render parse throw",
                    "[{ALLOW view Admin}]\n\nSecret text.\n\n[^1]: [[^1]\\[{y}]\n", Set.of( "Admin:view" ), true ),
            new Fixture( "documentation example next to a line that trips the parse budget",
                    "[{ALLOW view Admin}]\n\nWrite `[{ALLOW view All}]` to open a page.\n\n{edit" + " ".repeat( 3_000 ) + "x\n",
                    Set.of( "Admin:view" ), true ),
            new Fixture( "an ALLOW and a DENY for the same permission",
                    "[{ALLOW view Admin}]\n[{DENY view Guest}]\n", Set.of( "Admin:view" ), true ),
            new Fixture( "a math-only rule and a wrapped rule for the same permission",
                    "x $[{ALLOW edit alice}]$ y\n\n[{ALLOW edit bob,\n carol}]\n", Set.of( "alice:edit" ), true ),
            // Round-2 review: pages whose render parse throws hold rules only the render path reads (#13, #14).
            new Fixture( "a page whose render parse throws and whose only rule is wrapped",
                    "[{ALLOW edit alice,\n bob}]\n\nSecret.\n\n[^1]: [[^1]\\[{y}]\n", Set.of(), true ),
            new Fixture( "a page whose render parse throws, with a one-line and a wrapped rule",
                    "[{ALLOW view bob}]\n[{ALLOW edit alice,\n bob}]\n\n[^1]: [[^1]\\[{y}]\n", Set.of( "bob:view" ), true ),
            // Round-2 review: a rule line above lines that look like a frontmatter block (#15, #16).
            new Fixture( "a rule line above two delimiter lines",
                    "[{ALLOW edit Admin}]\n---\nIntro paragraph alpha.\n\nMore text beta.\n\n---\n\nRest gamma.\n",
                    Set.of( "Admin:edit" ), true ),
            new Fixture( "a rule line above a frontmatter-shaped block",
                    "[{ALLOW view All}]\n---\ntype: hub\ncluster: finance\n---\nBody.\n", Set.of( "All:view" ), true ) );

    private AclDirectiveFixtures() {
    }
}

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

import com.wikantik.auth.acl.AccessRules.Shape;

import java.util.Map;
import java.util.Set;

/**
 * Page texts that carry access rules in the shapes the text scan and the render path disagree on. Each fixture records
 * the ACL the old text scan enforced on it up to 2.5.5 (as {@code principal:action} pairs), what {@link AccessRules}
 * makes of it now ({@link #EXPECTED_LOCKS} for a lock; otherwise a {@code Rules} reading enforcing {@link #enforced}),
 * and whether its login must be hidden from readers: it must whenever either old reading reads a rule naming it. Shared
 * by the enforcement, stripping and rendering tests so each shape is checked on every surface.
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
                    "[{ALLOW view All}]\n---\ntype: hub\ncluster: finance\n---\nBody.\n", Set.of( "All:view" ), true ),
            // #104 open-up: the old text scan enforced an escaped example; it is literal text now.
            new Fixture( "an escaped example above a rule",
                    "Write \\[{ALLOW view Guest}] to restrict a page.\n\n[{ALLOW view Admin}]\n", Set.of( "Guest:view", "Admin:view" ),
                    true ) );

    /**
     * The shapes {@link AccessRules#read} locks, by fixture name (of {@link #ALL} and {@link #ENFORCEMENT_REPROS}); a
     * fixture not named here reads as rules and enforces exactly {@link #enforced}. Every fixture named here enforced its
     * {@code acl} (or the render path's reading, render first) before #104; it is locked now.
     */
    public static final Map< String, Set< Shape > > EXPECTED_LOCKS = Map.ofEntries(
            Map.entry( HTML_BLOCK.name(), Set.of( Shape.IN_HTML_BLOCK ) ),
            Map.entry( DENY.name(), Set.of( Shape.DENY ) ),
            Map.entry( KEYWORD_PREFIX.name(), Set.of( Shape.ALLOW_PREFIXED_WORD ) ),
            Map.entry( INLINE_MATH.name(), Set.of( Shape.IN_MATH ) ),
            Map.entry( MATH_FENCE.name(), Set.of( Shape.IN_MATH ) ),
            Map.entry( "footnote that makes the render parse throw", Set.of( Shape.UNREADABLE ) ),
            Map.entry( "documentation example next to a line that trips the parse budget", Set.of( Shape.UNREADABLE ) ),
            Map.entry( "an ALLOW and a DENY for the same permission", Set.of( Shape.DENY ) ),
            Map.entry( "a math-only rule and a wrapped rule for the same permission", Set.of( Shape.IN_MATH ) ),
            Map.entry( "a page whose render parse throws and whose only rule is wrapped", Set.of( Shape.UNREADABLE ) ),
            Map.entry( "a page whose render parse throws, with a one-line and a wrapped rule", Set.of( Shape.UNREADABLE ) ) );

    /**
     * What {@link AccessRules#read} enforces on the fixtures it reads differently from the old text scan, by fixture name:
     * the rules the text scan missed and the render path read (wrapped, upper-case permission) are rules now, as the
     * design's §5.3 says; an escaped example the text scan read is literal text.
     */
    public static final Map< String, Set< String > > EXPECTED_ACLS = Map.ofEntries(
            Map.entry( WRAPPED.name(), Set.of( "All:view", "Admin:edit", LOGIN + ":edit" ) ),
            Map.entry( QUOTED_WRAPPED.name(), Set.of( "Admin:edit", LOGIN + ":edit" ) ),
            Map.entry( UPPER_CASE.name(), Set.of( LOGIN + ":view" ) ),
            Map.entry( LONG_WRAPPED.name(), longWrappedAcl() ),
            Map.entry( "an escaped example above a rule", Set.of( "Admin:view" ) ) );

    private static Set< String > longWrappedAcl() {
        final Set< String > out = new java.util.HashSet<>();
        for ( int i = 0; i < 400; i++ ) {
            out.add( "user" + i + "@example.test:edit" );
        }
        out.add( LOGIN + ":edit" );
        return Set.copyOf( out );
    }

    /**
     * @param f a fixture of {@link #ALL} or {@link #ENFORCEMENT_REPROS} that {@link #EXPECTED_LOCKS} does not name
     * @return the ACL {@link AccessRules#read} enforces on it, as {@code principal:action} pairs
     */
    public static Set< String > enforced( final Fixture f ) {
        return EXPECTED_ACLS.getOrDefault( f.name(), f.acl() );
    }

    /**
     * @param ruleLines canonical rule lines ({@code ALLOW view Admin, bob})
     * @return their {@code principal:action} pairs, as the fixtures record an ACL
     */
    public static Set< String > pairs( final java.util.List< String > ruleLines ) {
        final Set< String > out = new java.util.HashSet<>();
        for ( final String line : ruleLines ) {
            final String[] words = line.split( " ", 3 );
            for ( final String principal : words[ 2 ].split( "," ) ) {
                out.add( principal.trim() + ":" + words[ 1 ] );
            }
        }
        return out;
    }

    /**
     * One row per access-rule shape of the #104 design (§5.1/§5.3).
     *
     * @param name      label
     * @param text      a page text holding the shape once
     * @param shape     what {@link AccessRules#read} classifies its one span as; {@code null} for literal text (no span)
     * @param ruleLines the rule lines a {@code Rules} reading of it holds, in canonical form; empty for a lock or literal
     *                  text
     */
    public record Shaped( String name, String text, Shape shape, java.util.List< String > ruleLines ) {
        Shaped( final String name, final String text, final Shape shape ) {
            this( name, text, shape, java.util.List.of() );
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** The one rule line most rows read as. */
    private static final java.util.List< String > ADMIN_VIEW = java.util.List.of( "ALLOW view Admin" );

    /**
     * Every shape, each alone on a page with body text after it. The design's §5.3 table, row by row: the unambiguous
     * shapes are rules in canonical form, an escaped one is literal text, the rest lock. {@code DENY} in any case, after
     * any whitespace, locks wherever it is outside code (frontmatter, HTML, math and links included). Any other span that
     * neither old reading took for a rule ({@code [{Allowance}]}, a lower-case rule in HTML or math, one in a YAML code
     * span) is literal text, not a lock: the page was open under both, and nobody can tell it apart from prose.
     */
    public static final java.util.List< Shaped > SHAPES = java.util.List.of(
            new Shaped( "canonical", "[{ALLOW view Admin}]\n\n" + BODY + "\n", Shape.CANONICAL, ADMIN_VIEW ),
            new Shaped( "canonical, with ()", "[{ALLOW view Admin}]()\n\n" + BODY + "\n", Shape.CANONICAL, ADMIN_VIEW ),
            new Shaped( "wrapped", "[{ALLOW view Admin,\n bob}]\n\n" + BODY + "\n", Shape.WRAPPED,
                    java.util.List.of( "ALLOW view Admin, bob" ) ),
            new Shaped( "wrapped over three lines", "[{ALLOW edit a,\n   b,\n\tc}]\n\n" + BODY + "\n", Shape.WRAPPED,
                    java.util.List.of( "ALLOW edit a, b, c" ) ),
            new Shaped( "wrapped in a blockquote, lower-case", "> [{allow edit a,\n> b}]\n\n" + BODY + "\n", Shape.WRAPPED,
                    java.util.List.of( "ALLOW edit a, b" ) ),
            new Shaped( "lower-case keyword", "[{allow view Admin}]\n\n" + BODY + "\n", Shape.CASE_VARIANT, ADMIN_VIEW ),
            new Shaped( "lower-case keyword, upper-case permission", "[{allow VIEW Bob}]\n\n" + BODY + "\n", Shape.CASE_VARIANT,
                    java.util.List.of( "ALLOW view Bob" ) ),
            new Shaped( "upper-case permission", "[{ALLOW VIEW Admin}]\n\n" + BODY + "\n", Shape.CASE_VARIANT, ADMIN_VIEW ),
            new Shaped( "mixed-case keyword and permission", "[{Allow Edit Admin}]\n\n" + BODY + "\n", Shape.CASE_VARIANT,
                    java.util.List.of( "ALLOW edit Admin" ) ),
            new Shaped( "space after the brace", "[{ ALLOW view Admin}]\n\n" + BODY + "\n", Shape.LEADING_SPACE, ADMIN_VIEW ),
            new Shaped( "space after the brace, upper-case permission", "[{  ALLOW  VIEW  Admin }]\n\n" + BODY + "\n",
                    Shape.LEADING_SPACE, ADMIN_VIEW ),
            new Shaped( "space after the brace, wrapped", "[{ ALLOW edit a,\n b}]\n\n" + BODY + "\n", Shape.WRAPPED,
                    java.util.List.of( "ALLOW edit a, b" ) ),
            new Shaped( "escaped", "\\[{ALLOW view Admin}]\n\n" + BODY + "\n", Shape.ESCAPED ),
            new Shaped( "a wrapped rule whose line break splits a name", "[{allow edit Jane\n Bob}]\n\n" + BODY + "\n",
                    Shape.WRAPPED_NAME ),
            new Shaped( "a wrapped rule whose line break splits a name, in a blockquote",
                    "> [{ALLOW edit Admin, Jane\n> Bob}]\n\n" + BODY + "\n", Shape.WRAPPED_NAME ),
            new Shaped( "wrapped, a comma right after the break", "> [{allow edit Jane\n> ,Bob}]\n\n" + BODY + "\n",
                    Shape.WRAPPED, java.util.List.of( "ALLOW edit Jane ,Bob" ) ),
            new Shaped( "wrapped between the permission and the names", "[{ALLOW edit\n Jane Doe}]\n\n" + BODY + "\n",
                    Shape.WRAPPED, java.util.List.of( "ALLOW edit Jane Doe" ) ),
            new Shaped( "a tab after the brace", "[{\tALLOW view Admin}]\n\n" + BODY + "\n", Shape.LEADING_SPACE, ADMIN_VIEW ),
            new Shaped( "a vertical tab after the brace", "[{\u000BALLOW view Admin}]\n\n" + BODY + "\n", Shape.LEADING_SPACE,
                    ADMIN_VIEW ),
            new Shaped( "an EM SPACE after the brace", "[{\u2003ALLOW view Admin}]\n\n" + BODY + "\n", Shape.UNREADABLE ),
            new Shaped( "a no-break space before the names", "[{ALLOW view\u00A0Admin}]\n\n" + BODY + "\n", Shape.BAD_PERMISSION ),
            new Shaped( "deny", "[{DENY view Admin}]\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "deny, wrapped", "[{DENY view Admin,\n bob}]\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "lower-case deny", "[{deny view Admin}]\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "mixed-case deny", "[{Deny view Admin}]\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "deny, a space after the brace", "[{ DENY view Admin}]\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "deny, a tab after the brace", "[{\tDENY view Admin}]\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "lower-case deny, wrapped", "[{deny edit a,\n Bob}]\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "lower-case deny, wrapped in a blockquote", "> [{deny edit a,\n> Bob}]\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "deny alone", "[{deny}]\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "deny in the frontmatter", "---\nsummary: [{DENY view Admin}]\n---\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "lower-case deny in the frontmatter", "---\nsummary: '[{deny view Admin}]'\n---\n" + BODY + "\n",
                    Shape.DENY ),
            new Shaped( "deny in an HTML block", "<div>\n[{deny view Admin}]\n</div>\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "deny in inline math", "Formula $[{DENY view Admin}]$ here.\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "deny in a link with a target", "[{deny view Admin}](http://example.test)\n\n" + BODY + "\n", Shape.DENY ),
            new Shaped( "deny, escaped", "\\[{deny view Admin}]\n\n" + BODY + "\n", Shape.ESCAPED ),
            new Shaped( "deny in a code span", "Write `[{deny view Admin}]` to restrict.\n\n" + BODY + "\n", null ),
            new Shaped( "deny in a fenced block", "```\n[{Deny view Admin}]\n```\n\n" + BODY + "\n", null ),
            new Shaped( "deny in a YAML code span in the frontmatter",
                    "---\nsummary: Write `[{deny view Admin}]` to restrict\n---\n" + BODY + "\n", null ),
            new Shaped( "a plugin name starting Deny", "[{Denylist}]\n\n" + BODY + "\n", null ),
            new Shaped( "ALLOWED", "[{ALLOWED view Admin}]\n\n" + BODY + "\n", Shape.ALLOW_PREFIXED_WORD ),
            new Shaped( "an upper-case plugin name starting ALLOW", "[{ALLOWANCE}]\n\n" + BODY + "\n", Shape.ALLOW_PREFIXED_WORD ),
            new Shaped( "in an HTML block", "<div>\n[{ALLOW view Admin}]\n</div>\n\n" + BODY + "\n", Shape.IN_HTML_BLOCK ),
            new Shaped( "in an HTML comment", "<!-- [{ALLOW view Admin}] -->\n\n" + BODY + "\n", Shape.IN_HTML_BLOCK ),
            new Shaped( "in inline math", "Formula $[{ALLOW view Admin}]$ here.\n\n" + BODY + "\n", Shape.IN_MATH ),
            new Shaped( "in display math", "$$\n[{ALLOW view Admin}]\n$$\n\n" + BODY + "\n", Shape.IN_MATH ),
            new Shaped( "in the frontmatter", "---\nsummary: [{ALLOW view Admin}]\n---\n" + BODY + "\n", Shape.IN_FRONTMATTER ),
            new Shaped( "a link with a target", "[{ALLOW view Admin}](http://example.test)\n\n" + BODY + "\n", Shape.LINK_WITH_TARGET ),
            new Shaped( "an image", "![{ALLOW view Admin}](x.png)\n\n" + BODY + "\n", Shape.LINK_WITH_TARGET ),
            new Shaped( "a permission the ACL parser refuses", "[{ALLOW viewbob Admin}]\n\n" + BODY + "\n", Shape.BAD_PERMISSION ),
            new Shaped( "a refused permission, lower-case keyword", "[{allow viewbob Admin}]\n\n" + BODY + "\n",
                    Shape.BAD_PERMISSION ),
            new Shaped( "no permission", "[{ALLOW}]\n\n" + BODY + "\n", Shape.BAD_PERMISSION ),
            new Shaped( "a rule only the render path reads", "[{ALLOW view Admin]()\n\n" + BODY + "\n", Shape.UNREADABLE ),
            new Shaped( "in a code span", "Write `[{ALLOW view Admin}]` to restrict.\n\n" + BODY + "\n", null ),
            new Shaped( "in a fenced block", "```\n[{ALLOW view Admin}]\n```\n\n" + BODY + "\n", null ),
            new Shaped( "lower-case, in a code span", "Write `[{allow VIEW Admin}]` to restrict.\n\n" + BODY + "\n", null ),
            new Shaped( "a plugin name starting Allow", "[{Allowance}]\n\n" + BODY + "\n", null ),
            new Shaped( "lower-case, in an HTML block", "<div>\n[{allow view Admin}]\n</div>\n\n" + BODY + "\n", null ),
            new Shaped( "lower-case, in inline math", "Formula $[{allow view Admin}]$ here.\n\n" + BODY + "\n", null ),
            new Shaped( "in a YAML code span in the frontmatter",
                    "---\nsummary: Write `[{ALLOW view Admin}]` to restrict\n---\n" + BODY + "\n", null ),
            new Shaped( "lower-case, in the frontmatter", "---\nsummary: '[{allow view Admin}]'\n---\n" + BODY + "\n", null ) );

    /**
     * Texts built to make a per-span scan cost the square of the text: openers that share one closer give overlapping
     * candidates, and a field that holds many rules sits above every one of them. Each must be read (and stripped) in
     * time linear in its length.
     */
    public static final java.util.List< Shaped > ADVERSARIAL = java.util.List.of(
            new Shaped( "openers sharing one closer, a keyword with no space after it", "[{allowa".repeat( 40_000 ) + "}]", null ),
            new Shaped( "openers sharing one closer, DENY with no space after it", "[{DENYa".repeat( 40_000 ) + "}]", null ),
            new Shaped( "openers sharing one closer, no-break spaces after the keyword", "[{allow\u00A0".repeat( 40_000 ) + "}]",
                    null ),
            new Shaped( "openers sharing one closer, a space after each brace", "[{ allowa".repeat( 40_000 ) + "}]", null ),
            new Shaped( "openers sharing one closer, a lower-case deny each", "[{deny edit alice ".repeat( 40_000 ) + "\nx}]",
                    null ),
            new Shaped( "many openers and spaces before one keyword", "[{ ".repeat( 100_000 ) + "allow view x}]", null ),
            new Shaped( "one frontmatter field holding many rules", "---\ntags:\n" + "  - '[{ALLOW view x}]'\n".repeat( 20_000 )
                    + "---\nBody.\n", null ),
            new Shaped( "one frontmatter field holding many lower-case denies",
                    "---\ntags:\n" + "  - '[{deny view x}]'\n".repeat( 20_000 ) + "---\nBody.\n", null ) );

    private AclDirectiveFixtures() {
    }
}

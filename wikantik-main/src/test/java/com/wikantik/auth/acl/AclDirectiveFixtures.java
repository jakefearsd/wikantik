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
 * Page texts that carry access rules in the shapes the text scan and the render path disagreed on, with the ACL the
 * wiki must enforce for each (as {@code principal:action} pairs). Shared by the enforcement, stripping and rendering
 * tests so each shape is checked on every surface.
 */
public final class AclDirectiveFixtures {

    /** A login that is an email address, the worst case: it must reach no reader. */
    public static final String LOGIN = "alice@example.test";

    /** One fixture: a page text, the ACL it must enforce, and whether readers must still see {@link #BODY}. */
    public record Fixture( String name, String text, Set< String > acl ) {
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
            Set.of( "All:view", "Admin:edit", LOGIN + ":edit" ) );

    /** Inside an HTML block the fence is plain text: the text scan enforces the rule, the renderer prints it. */
    public static final Fixture HTML_BLOCK = new Fixture( "html block",
            "[{ALLOW view All}]\n\n<details>\n```\n[{ALLOW edit Admin," + LOGIN + "}]\n```\n</details>\n\n" + BODY + "\n",
            Set.of( "All:view", "Admin:edit", LOGIN + ":edit" ) );

    /** A link label may run over several lines (no blank line): the render path enforces it, the one-line scan did not. */
    public static final Fixture WRAPPED = new Fixture( "wrapped",
            "[{ALLOW view All}]\n[{ALLOW edit Admin,\n " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of( "All:view", "Admin:edit", LOGIN + ":edit" ) );

    /** Wrapped inside a blockquote: the renderer reads the label without the continuation line's {@code >}. */
    public static final Fixture QUOTED_WRAPPED = new Fixture( "wrapped in a blockquote",
            "> [{ALLOW edit Admin,\n> " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of( "Admin:edit", LOGIN + ":edit" ) );

    /** The render path reads {@code DENY} as a grant (its parser skips the first word); the text scan ignored it. */
    public static final Fixture DENY = new Fixture( "deny",
            "[{ALLOW view All}]\n[{DENY edit " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of( "All:view", LOGIN + ":edit" ) );

    /** The permission is matched case-insensitively by the render path, case-sensitively by the text scan. */
    public static final Fixture UPPER_CASE = new Fixture( "upper-case permission",
            "[{ALLOW VIEW " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of( LOGIN + ":view" ) );

    /** {@code ALLOWED}: the render path only checks the prefix and skips the first word. */
    public static final Fixture KEYWORD_PREFIX = new Fixture( "keyword with a suffix",
            "[{ALLOWED edit " + LOGIN + "}]\n\n" + BODY + "\n",
            Set.of( LOGIN + ":edit" ) );

    /** In inline math the renderer never sees a link, but the text scan (no math) enforces it. */
    public static final Fixture INLINE_MATH = new Fixture( "inline math",
            "Formula $[{ALLOW edit " + LOGIN + "}]$ here.\n\n" + BODY + "\n",
            Set.of( LOGIN + ":edit" ) );

    /** In a code span: documentation for both. */
    public static final Fixture CODE_SPAN = new Fixture( "code span",
            "Write `[{ALLOW edit " + LOGIN + "}]` to restrict a page.\n\n" + BODY + "\n",
            Set.of() );

    /** A blank line ends a link label: nobody enforces this, however it looks. */
    public static final Fixture BLANK_LINE = new Fixture( "blank line inside",
            "Use [{ALLOW edit Admin,\n\n" + LOGIN + "}] like so.\n\n" + BODY + "\n",
            Set.of() );

    /**
     * An unterminated opener in a frontmatter value, a body closer in code: no directive at all. The renderer never
     * reads frontmatter, and the one-line scan cannot cross the line ends.
     */
    public static final Fixture FRONTMATTER_OPENER = new Fixture( "opener in frontmatter",
            "---\ntitle: Access rules\nverified_by: " + LOGIN + "\nsummary: How to use [{ALLOW view and [{DENY edit rules\n---\n"
                    + "Use `[{ALLOW edit Alice}]` to restrict a page.\n\n" + BODY + "\n",
            Set.of() );

    /** A long wrapped principal list: the renderer bounds a link label by no length, so neither may anything else. */
    public static final Fixture LONG_WRAPPED = longWrapped();

    private static Fixture longWrapped() {
        final StringBuilder text = new StringBuilder( "[{ALLOW edit " );
        final java.util.Set< String > acl = new java.util.HashSet<>();
        for ( int i = 0; i < 400; i++ ) {
            text.append( "user" ).append( i ).append( "@example.test,\n " );
            acl.add( "user" + i + "@example.test:edit" );
        }
        text.append( LOGIN ).append( "}]\n\n" ).append( BODY ).append( "\n" );
        acl.add( LOGIN + ":edit" );
        return new Fixture( "long wrapped list (" + text.length() + " chars)", text.toString(), Set.copyOf( acl ) );
    }

    /** Every fixture. */
    public static final java.util.List< Fixture > ALL = java.util.List.of( PLAIN, HTML_BLOCK, WRAPPED, QUOTED_WRAPPED, DENY,
            UPPER_CASE, KEYWORD_PREFIX, INLINE_MATH, CODE_SPAN, BLANK_LINE, FRONTMATTER_OPENER, LONG_WRAPPED );

    private AclDirectiveFixtures() {
    }
}

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

import com.wikantik.util.AclDirectives;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Safety net between two implementations of "what is an access rule": {@link DefaultAclManager} decides
 * what the wiki <em>enforces</em>, {@link AclDirectives} (wikantik-util, which cannot depend on it)
 * decides what is <em>hidden</em> from readers who may not edit. The invariant that matters for
 * disclosure is one-directional — nothing the ACL manager enforces may survive {@code strip} — so that is
 * what every test here asserts. {@code strip} may remove more (it also removes {@code DENY}, and it does
 * not trust code it cannot be certain about); it must never remove less.
 */
class AclDirectivesParityTest {

    /** Stands for the name a leak must never reveal. */
    private static final String LOGIN = "leak-probe@example.test";

    private static final Pattern TRAILING_EMPTY_PARENS = Pattern.compile( "^\\(\\)" );

    /** Bodies in which the ACL manager enforces at least one directive, in many placements and spellings. */
    static Stream< String > bodiesWithEnforcedDirectives() {
        return Stream.of(
                "[{ALLOW view Admin}]\nSecret content.",
                "Intro. [{ALLOW view Admin}] more text.",
                "[{ALLOW view Admin}] then [{ALLOW edit " + LOGIN + "}]",
                "x [{  ALLOW  view  Admin  }] y [{ALLOW edit " + LOGIN + "}]",
                "[{ALLOW view Admin," + LOGIN + "}]",
                "The `pattern` then [{ALLOW edit " + LOGIN + "}].",
                "`a` [{ALLOW edit " + LOGIN + "}] `c`",
                "[{ALLOW view Admin}] syntax is `[{ALLOW edit Bob}]`.",
                "Example `[{ALLOW view Admin}]` but really [{ALLOW edit " + LOGIN + "}].",
                "``[{ALLOW view Admin}]`` and then [{ALLOW edit " + LOGIN + "}]",
                "```\n[{ALLOW view Admin}]\n```\n[{ALLOW edit " + LOGIN + "}]\n",
                "~~~\n[{ALLOW view Admin}]\n~~~\n[{ALLOW edit " + LOGIN + "}]\n",
                "[{ALLOW edit " + LOGIN + "}]\n```\n[{ALLOW view Admin}]\n```\n",
                "[{ALLOW edit " + LOGIN + "}]()\n[{ALLOW view All}]()\n# Title\n",
                "[{ALLOW edit Admin}]()[{ALLOW view All}]()[{ALLOW edit " + LOGIN + "}]()",
                "---\ntitle: T\ncluster: c\n---\n[{ALLOW view All}]\n[{ALLOW edit " + LOGIN + "}]\n\n# T\n\nBody.\n",
                "[{ALLOW edit\n" + LOGIN + "}]",
                "[{ALLOW edit a}b" + LOGIN + "}]",
                // Lookalikes of code that is NOT code: the directive is live and must go.
                "    echo `date\n[{ALLOW edit " + LOGIN + "}] and `x`",
                "    ```\n[{ALLOW edit " + LOGIN + "}]\n    ```\n",
                "a ` b\n\n[{ALLOW edit " + LOGIN + "}]\n\nc ` d",
                "\\`[{ALLOW edit " + LOGIN + "}]\\`",
                "[{ALLOW edit " + LOGIN + "}]\r\n# Title\r\nBody.\r\n" );
    }

    @ParameterizedTest
    @MethodSource( "bodiesWithEnforcedDirectives" )
    void nothingTheAclManagerEnforcesSurvivesStripping( final String body ) {
        final List< String > enforced = DefaultAclManager.aclDirectives( body );
        assertFalse( enforced.isEmpty(), "fixture is vacuous: the ACL manager enforces nothing in: " + body );

        final String stripped = AclDirectives.strip( body );

        for ( final String directive : enforced ) {
            assertFalse( stripped.contains( directive ), "directive survived: " + directive + " in " + stripped );
        }
        assertEquals( List.of(), DefaultAclManager.aclDirectives( stripped ),
                "the ACL manager still finds a directive after stripping: " + stripped );
        assertFalse( stripped.contains( LOGIN ), "the login survived: " + stripped );
    }

    @ParameterizedTest
    @MethodSource( "bodiesWithEnforcedDirectives" )
    void strippingTouchesNothingExceptDirectives( final String body ) {
        // Rebuild the expected text by deleting only the directives the ACL manager reported (plus an
        // immediately trailing "()"); every fixture gives its directives distinct text from any code
        // sample beside them, so the first occurrence is the enforced one.
        String expected = body;
        for ( final String directive : DefaultAclManager.aclDirectives( body ) ) {
            final int at = expected.indexOf( directive );
            final int end = at + directive.length();
            final int withParens = TRAILING_EMPTY_PARENS.matcher( expected.substring( end ) ).find() ? end + 2 : end;
            expected = expected.substring( 0, at ) + expected.substring( withParens );
        }
        assertEquals( expected, AclDirectives.strip( body ) );
    }

    /**
     * The one deliberate divergence in the cautious direction: an unclosed fence makes the renderer treat
     * the rest of the page as code, so the ACL manager enforces nothing there, but {@code strip} does not
     * trust a fence that never closes and removes the directive anyway.
     */
    @Test
    void anUnclosedFenceIsHiddenEvenThoughTheAclManagerIgnoresIt() {
        final String body = "```\ncode\n[{ALLOW edit " + LOGIN + "}]\nan unclosed fence";
        assertEquals( List.of(), DefaultAclManager.aclDirectives( body ) );
        assertEquals( "```\ncode\n\nan unclosed fence", AclDirectives.strip( body ) );
    }

    /** Removing the inner directive joins "[{ALLOW vi" and "ew a}]" into another one, which must go too. */
    @Test
    void removalCannotSpliceAFreshDirectiveTogether() {
        final String body = "[{ALLOW vi[{ALLOW view " + LOGIN + "}]ew a}]";
        assertEquals( List.of( "[{ALLOW view " + LOGIN + "}]" ), DefaultAclManager.aclDirectives( body ) );
        final String stripped = AclDirectives.strip( body );
        assertEquals( "", stripped );
        assertEquals( List.of(), DefaultAclManager.aclDirectives( stripped ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "comment", "delete", "edit", "modify", "rename", "upload", "view" } )
    void everyPermissionTheAclManagerAcceptsIsStripped( final String action ) {
        final String body = "[{ALLOW " + action + " " + LOGIN + "}]";
        assertEquals( List.of( body ), DefaultAclManager.aclDirectives( body ) );
        assertEquals( "", AclDirectives.strip( body ) );
    }

    /** Documentation the ACL manager ignores is documentation {@code strip} keeps, unchanged. */
    @ParameterizedTest
    @ValueSource( strings = {
            "Use `[{ALLOW view Admin}]` to restrict.",
            "Code: ``[{ALLOW view Admin}]`` end.",
            "- ... enforced across REST, MCP, and the UI; inline `[{ALLOW view Admin}]` ACLs in page bodies.",
            "```\n[{ALLOW view Admin}]\n```\n",
            "~~~\n[{ALLOW view Admin}]\n[{ALLOW edit Bob}]\n~~~\n",
            "```\n[{ALLOW view Admin}]\n[{ALLOW edit Bob}]\n```",
            "Just prose with a [link](x) and `code`, no rules.",
    } )
    void codeSamplesTheAclManagerIgnoresAreKept( final String body ) {
        assertEquals( List.of(), DefaultAclManager.aclDirectives( body ), "fixture must be ignored by the ACL manager" );
        assertEquals( body, AclDirectives.strip( body ) );
    }

    /**
     * The same two properties over the real shipped corpus — about twelve hundred pages, several of which
     * document or use access rules. Skipped when the corpus is not on disk (a module built outside the repo).
     */
    @Test
    void shippedCorpusHonoursTheInvariant() throws IOException {
        final Path corpus = Path.of( "..", "docs", "wikantik-pages" );
        Assumptions.assumeTrue( Files.isDirectory( corpus ), "corpus not found at " + corpus.toAbsolutePath() );

        int withDirectives = 0;
        try ( Stream< Path > files = Files.list( corpus ) ) {
            for ( final Path file : files.filter( f -> f.toString().endsWith( ".md" ) ).sorted().toList() ) {
                final String body = Files.readString( file, StandardCharsets.UTF_8 );
                final List< String > enforced = DefaultAclManager.aclDirectives( body );
                final String stripped = AclDirectives.strip( body );
                assertEquals( List.of(), DefaultAclManager.aclDirectives( stripped ),
                        "directive survived stripping in " + file.getFileName() );
                if ( !enforced.isEmpty() ) {
                    withDirectives++;
                    for ( final String directive : enforced ) {
                        assertFalse( stripped.contains( directive ), file.getFileName() + " still has " + directive );
                    }
                }
            }
        }
        assertTrue( withDirectives >= 1, "expected at least one shipped page with a live directive (RejectedMessage)" );
    }
}

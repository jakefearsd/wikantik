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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link DefaultAclManager#aclDirectives(String)} — the
 * Markdown-aware ACL scanner. A {@code [{ALLOW …}]} directive is only an
 * enforceable access rule when it sits in ordinary page text; the same syntax
 * shown inside a Markdown code span or code block is documentation and must be
 * ignored. The overriding safety rule: a genuine restriction outside code must
 * NEVER be dropped (fail closed toward enforcement).
 */
class DefaultAclManagerCodeSpanTest {

    // ---- directives in ordinary text are enforceable ----

    @Test
    void plainAclOutsideCodeIsReturned() {
        assertEquals( List.of( "[{ALLOW view Admin}]" ),
            DefaultAclManager.aclDirectives( "Intro. [{ALLOW view Admin}] more text." ) );
    }

    @Test
    void genuineRestrictiveViewAclIsNeverDropped() {
        // SECURITY regression: a real restriction must always be enforced.
        assertEquals( List.of( "[{ALLOW view Admin}]" ),
            DefaultAclManager.aclDirectives( "[{ALLOW view Admin}]\nSecret content." ) );
    }

    @Test
    void multipleRealAclsReturnedInOrder() {
        assertEquals( List.of( "[{ALLOW view Admin}]", "[{ALLOW edit Bob}]" ),
            DefaultAclManager.aclDirectives( "[{ALLOW view Admin}] then [{ALLOW edit Bob}]" ) );
    }

    @Test
    void whitespaceVariantOutsideCodeIsReturned() {
        assertEquals( List.of( "[{  ALLOW  view  Admin  }]" ),
            DefaultAclManager.aclDirectives( "x [{  ALLOW  view  Admin  }] y" ) );
    }

    @Test
    void multiplePrincipalsOutsideCodeReturned() {
        assertEquals( List.of( "[{ALLOW view Admin,Authenticated}]" ),
            DefaultAclManager.aclDirectives( "[{ALLOW view Admin,Authenticated}]" ) );
    }

    @Test
    void aclImmediatelyAfterInlineCodeIsReturned() {
        assertEquals( List.of( "[{ALLOW edit Admin}]" ),
            DefaultAclManager.aclDirectives( "The `pattern` then [{ALLOW edit Admin}]." ) );
    }

    @Test
    void aclBetweenTwoInlineCodeSpansIsReturned() {
        assertEquals( List.of( "[{ALLOW edit Bob}]" ),
            DefaultAclManager.aclDirectives( "`a` [{ALLOW edit Bob}] `c`" ) );
    }

    @Test
    void realAclThenCodeExampleReturnsOnlyReal() {
        assertEquals( List.of( "[{ALLOW view Admin}]" ),
            DefaultAclManager.aclDirectives( "[{ALLOW view Admin}] syntax is `[{ALLOW edit Bob}]`." ) );
    }

    // ---- directives inside code are documentation, not rules ----

    @Test
    void inlineCodeAclIsIgnored() {
        assertEquals( List.of(),
            DefaultAclManager.aclDirectives( "Use `[{ALLOW view Admin}]` to restrict." ) );
    }

    @Test
    void wikantikArchitectureDocExampleIsIgnored() {
        // The exact shape that hid WikantikArchitecture from anonymous users.
        final String line = "- ... enforced across REST, MCP, and the UI; "
            + "inline `[{ALLOW view Admin}]` ACLs in page bodies.";
        assertEquals( List.of(), DefaultAclManager.aclDirectives( line ) );
    }

    @Test
    void doubleBacktickInlineCodeIsIgnored() {
        assertEquals( List.of(),
            DefaultAclManager.aclDirectives( "Code: ``[{ALLOW view Admin}]`` end." ) );
    }

    @Test
    void editAclInInlineCodeIsIgnored() {
        assertEquals( List.of(),
            DefaultAclManager.aclDirectives( "Restrict editing with `[{ALLOW edit Admin}]`." ) );
    }

    @Test
    void aclInsideSecondInlineCodeSpanIsIgnored() {
        assertEquals( List.of(),
            DefaultAclManager.aclDirectives( "`first` and `[{ALLOW view Admin}]`" ) );
    }

    @Test
    void fencedCodeBlockAclIsIgnored() {
        assertEquals( List.of(),
            DefaultAclManager.aclDirectives( "```\n[{ALLOW view Admin}]\n```\n" ) );
    }

    @Test
    void tildeFencedCodeBlockAclIsIgnored() {
        assertEquals( List.of(),
            DefaultAclManager.aclDirectives( "~~~\n[{ALLOW view Admin}]\n~~~\n" ) );
    }

    @Test
    void fencedBlockWithMultipleAclsAllIgnored() {
        assertEquals( List.of(),
            DefaultAclManager.aclDirectives( "```\n[{ALLOW view Admin}]\n[{ALLOW edit Bob}]\n```" ) );
    }

    @Test
    void indentedCodeBlockAclIsIgnored() {
        assertEquals( List.of(),
            DefaultAclManager.aclDirectives( "Before.\n\n    [{ALLOW view Admin}]\n\nAfter.\n" ) );
    }

    // ---- mixed and degenerate inputs ----

    @Test
    void realAclAmongCodeExampleReturnsOnlyReal() {
        assertEquals( List.of( "[{ALLOW edit Bob}]" ),
            DefaultAclManager.aclDirectives( "Example `[{ALLOW view Admin}]` but really [{ALLOW edit Bob}]." ) );
    }

    @Test
    void nullTextReturnsEmpty() {
        assertEquals( List.of(), DefaultAclManager.aclDirectives( null ) );
    }

    @Test
    void emptyTextReturnsEmpty() {
        assertEquals( List.of(), DefaultAclManager.aclDirectives( "" ) );
    }

    @Test
    void textWithNoDirectiveReturnsEmpty() {
        assertEquals( List.of(),
            DefaultAclManager.aclDirectives( "Just prose with a [link](x) and `code`, no rules." ) );
    }
}

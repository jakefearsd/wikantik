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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  The CLI keeps its own copy of the code-region exclusion in {@code EnforcedAclDirectives} (package-private in
 *  wikantik-main). This pins the copy to the original: if either changes, the "old text scan" column would silently
 *  go wrong, so they must agree on every fixture.
 */
class AclShapeAuditDriftTest {

    private static final List< String > FIXTURES = List.of(
        "plain\n[{ALLOW view Admin}]\n",
        "inline `[{ALLOW view Admin}]` span and [{ALLOW edit Bob}] real\n",
        "```\n[{ALLOW view Admin}]\n```\n[{ALLOW edit Bob}]\n",
        "~~~text\n[{ALLOW view Admin}]\n~~~\n",
        "para\n\n    [{ALLOW view Admin}]\n\n[{ALLOW comment Carol}]\n",
        "---\ntitle: `x`\nnote: \"[{ALLOW view Admin}]\"\n---\n[{ALLOW view Bob}]\n",
        "---\ntitle: a `tick\n---\nbody `[{ALLOW view Admin}]` tail [{ALLOW edit Bob}]\n",
        "[{DENY view Bob}] [{ALLOW view Admin, Bob}]\n",
        "\\[{ALLOW view Admin}]\n",
        "" );

    @SuppressWarnings( "unchecked" )
    private static List< String > enforced( final String text ) throws ReflectiveOperationException {
        final Class< ? > c = Class.forName( "com.wikantik.auth.acl.EnforcedAclDirectives" );
        final Method m = c.getDeclaredMethod( "directives", String.class );
        m.setAccessible( true );
        return ( List< String > ) m.invoke( null, text );
    }

    @Test
    void cli_code_exclusion_matches_the_enforced_reading() throws ReflectiveOperationException {
        boolean anyNonEmpty = false;
        for ( final String text : FIXTURES ) {
            final List< String > expected = enforced( text );
            assertEquals( expected, AclShapeAudit.oldTextScanDirectives( text ), "drift on: " + text );
            anyNonEmpty |= !expected.isEmpty();
        }
        assertTrue( anyNonEmpty, "the fixtures exercise at least one enforced directive" );
    }
}

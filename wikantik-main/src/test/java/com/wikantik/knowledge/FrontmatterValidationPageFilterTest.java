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
package com.wikantik.knowledge;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FilterException;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrontmatterValidationPageFilterTest {

    private FrontmatterValidationPageFilter filter( final boolean enabled ) {
        final Properties p = new Properties();
        p.setProperty( FrontmatterValidationPageFilter.PROP_ENFORCEMENT_ENABLED,
                Boolean.toString( enabled ) );
        return new FrontmatterValidationPageFilter( p );
    }

    private static Context contextFor( final String pageName ) {
        final Context ctx = mock( Context.class );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( pageName );
        when( ctx.getPage() ).thenReturn( page );
        return ctx;
    }

    @Test
    void preSave_rejectsUnquotedColonInTitle() {
        // The recurring failure mode: agent or human writes
        // 'title: Woodworking Joinery: Structural Mechanics' without quotes.
        // SnakeYAML rejects; the filter must surface that to the caller instead
        // of letting the page save with empty metadata.
        final String bad = "---\n"
                + "title: Woodworking Joinery: Structural Mechanics\n"
                + "---\n"
                + "body";
        final FilterException ex = assertThrows( FilterException.class,
                () -> filter( true ).preSave( contextFor( "Joinery" ), bad ) );
        assertTrue( ex.getMessage().contains( "Joinery" ),
                "page name must surface so operators can find the offender" );
        assertTrue( ex.getMessage().contains( "double quotes" )
                || ex.getMessage().contains( "Wrap values" ),
                "error must include the quoting hint — got: " + ex.getMessage() );
    }

    @Test
    void preSave_acceptsWellFormedFrontmatter() throws Exception {
        final String good = "---\n"
                + "title: \"Foo: Bar\"\n"
                + "type: article\n"
                + "---\n"
                + "body";
        final String out = filter( true ).preSave( contextFor( "Foo" ), good );
        assertEquals( good, out, "filter must pass through unchanged when YAML is valid" );
    }

    @Test
    void preSave_acceptsPageWithoutFrontmatter() throws Exception {
        final String body = "Just markdown body, no frontmatter here.";
        final String out = filter( true ).preSave( contextFor( "Foo" ), body );
        assertEquals( body, out );
    }

    @Test
    void preSave_acceptsEmptyFrontmatter() throws Exception {
        final String empty = "---\n---\nbody";
        final String out = filter( true ).preSave( contextFor( "Foo" ), empty );
        assertEquals( empty, out );
    }

    @Test
    void preSave_disabledFlagBypassesValidation() throws Exception {
        // Escape hatch for the migration window: operator can flip the flag off
        // while running the audit pass, without the filter blocking saves of
        // pages that already have malformed frontmatter.
        final String bad = "---\ntitle: Unquoted: Colon\n---\nbody";
        final String out = filter( false ).preSave( contextFor( "Foo" ), bad );
        assertEquals( bad, out, "disabled filter must pass even malformed pages through" );
    }

    @Test
    void preSave_acceptsNullAndEmptyContent() throws Exception {
        // Defensive — JSPWiki occasionally hands filters null/empty content;
        // we must not NPE.
        assertEquals( null, filter( true ).preSave( contextFor( "Foo" ), null ) );
        assertEquals( "", filter( true ).preSave( contextFor( "Foo" ), "" ) );
    }
}

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
package com.wikantik.knowledge.agent;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.structure.PageDescriptor;
import com.wikantik.api.structure.PageType;
import com.wikantik.api.structure.StructuralIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RunbookValidationPageFilterTest {

    private StructuralIndexService idx;
    private PageManager pm;

    @BeforeEach
    void setUp() throws ProviderException {
        idx = mock( StructuralIndexService.class );
        pm = mock( PageManager.class );
        when( idx.getByCanonicalId( anyString() ) ).thenReturn( Optional.empty() );
        when( pm.pageExists( anyString() ) ).thenReturn( false );
    }

    private RunbookValidationPageFilter filter( final boolean enabled ) {
        final Properties p = new Properties();
        p.setProperty( RunbookValidationPageFilter.PROP_ENFORCEMENT_ENABLED,
                Boolean.toString( enabled ) );
        return new RunbookValidationPageFilter( idx, pm, p );
    }

    private static Context contextFor( final String pageName ) {
        final Context ctx = mock( Context.class );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( pageName );
        when( ctx.getPage() ).thenReturn( page );
        return ctx;
    }

    @Test
    void non_runbook_pages_are_passed_through_unchanged() throws Exception {
        final String content = "---\ntype: article\n---\nbody\n";
        final String out = filter( true ).preSave( contextFor( "Foo" ), content );
        assertEquals( content, out );
    }

    @Test
    void valid_runbook_passes_through_unchanged() throws Exception {
        final String content = "---\n" +
                "type: runbook\n" +
                "runbook:\n" +
                "  when_to_use:\n" +
                "    - Agent needs X\n" +
                "  steps:\n" +
                "    - Call A\n" +
                "    - Then call B\n" +
                "  pitfalls:\n" +
                "    - (none known)\n" +
                "---\nbody\n";
        final String out = filter( true ).preSave( contextFor( "Foo" ), content );
        assertEquals( content, out );
    }

    @Test
    void invalid_runbook_throws_filter_exception() {
        final String content = "---\n" +
                "type: runbook\n" +
                "runbook:\n" +
                "  when_to_use:\n" +
                "    - Agent needs X\n" +
                "  steps:\n" +
                "    - only one step\n" +
                "  pitfalls:\n" +
                "    - (none known)\n" +
                "---\nbody\n";
        final FilterException ex = assertThrows( FilterException.class,
                () -> filter( true ).preSave( contextFor( "Foo" ), content ) );
        assertTrue( ex.getMessage().contains( "STEPS_TOO_FEW" )
                 || ex.getMessage().toLowerCase().contains( "steps" ),
                () -> "expected message to name the issue, was: " + ex.getMessage() );
    }

    @Test
    void enforcement_disabled_lets_invalid_runbooks_through() throws Exception {
        final String content = "---\n" +
                "type: runbook\n" +
                "runbook:\n" +
                "  when_to_use:\n" +
                "    - Agent needs X\n" +
                "  steps:\n" +
                "    - only one\n" +
                "  pitfalls:\n" +
                "    - (none known)\n" +
                "---\nbody\n";
        final String out = filter( false ).preSave( contextFor( "Foo" ), content );
        assertEquals( content, out );
    }

    @Test
    void missing_runbook_block_on_runbook_type_throws() {
        final String content = "---\ntype: runbook\n---\nbody\n";
        assertThrows( FilterException.class,
                () -> filter( true ).preSave( contextFor( "Foo" ), content ) );
    }

    @Test
    void canonical_id_reference_resolves_via_index() throws Exception {
        when( idx.getByCanonicalId( "01KQ0PEXAMPLECANONICAL12345" ) )
                .thenReturn( Optional.of( new PageDescriptor(
                        "01KQ0PEXAMPLECANONICAL12345", "Target", "Target Title",
                        PageType.ARTICLE, null, List.of(), null, Instant.now() ) ) );
        final String content = "---\n" +
                "type: runbook\n" +
                "runbook:\n" +
                "  when_to_use:\n" +
                "    - x\n" +
                "  steps:\n" +
                "    - a\n" +
                "    - b\n" +
                "  pitfalls:\n" +
                "    - (none known)\n" +
                "  references:\n" +
                "    - 01KQ0PEXAMPLECANONICAL12345\n" +
                "---\nbody\n";
        final String out = filter( true ).preSave( contextFor( "Foo" ), content );
        assertEquals( content, out );
    }

    @Test
    void page_title_reference_resolves_via_page_manager() throws Exception {
        when( pm.pageExists( "HybridRetrieval" ) ).thenReturn( true );
        final String content = "---\n" +
                "type: runbook\n" +
                "runbook:\n" +
                "  when_to_use:\n" +
                "    - x\n" +
                "  steps:\n" +
                "    - a\n" +
                "    - b\n" +
                "  pitfalls:\n" +
                "    - (none known)\n" +
                "  references:\n" +
                "    - HybridRetrieval\n" +
                "---\nbody\n";
        final String out = filter( true ).preSave( contextFor( "Foo" ), content );
        assertEquals( content, out );
    }
}

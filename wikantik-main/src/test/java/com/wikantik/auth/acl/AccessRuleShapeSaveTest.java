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

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.filters.FilterManager;
import com.wikantik.knowledge.FrontmatterDefaultsFilter;
import com.wikantik.pagegraph.spine.StructuralSpinePageFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The save refusal on the engine's own save path ({@link PageManager#saveText}), which every surface (REST, MCP, import)
 * goes through: a new disputed access rule is refused; a locked page keeps its stored disputed span through other edits;
 * removing the span unlocks the page on the next permission check.
 */
class AccessRuleShapeSaveTest {

    private static final String PAGE = "AccessRuleShapeSavePage";
    private static final String DENY = "[{DENY view Bob}]";

    private final TestEngine engine = TestEngine.build();

    @AfterEach
    void tearDown() {
        engine.deleteQuietly( PAGE );
        engine.stop();
    }

    private PageManager pages() {
        return engine.getManager( PageManager.class );
    }

    /** The stored latest text, line ends as written (the store keeps CRLF). */
    private String stored() {
        return pages().getPureText( PAGE, PageProvider.LATEST_VERSION ).replace( "\r\n", "\n" );
    }

    private boolean locked() {
        final Page page = pages().getPage( PAGE );
        return engine.getManager( AclManager.class ).getPermissions( page ).getAclEntry( DefaultAclManager.LOCKED_PRINCIPAL ) != null;
    }

    /** A page that is locked already: written to the store directly, as a page saved before the refusal existed is. */
    private void storeLockedPage( final String text ) throws Exception {
        engine.saveText( PAGE, "Placeholder.\n" );
        final Page page = pages().getPage( PAGE );
        pages().getProvider().putPageText( page, text );
    }

    @Test
    void aSaveThatAddsADenyRuleIsRefusedAndNothingIsWritten() throws Exception {
        engine.saveText( PAGE, "Open page.\n" );
        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class,
                () -> engine.saveText( PAGE, "Open page.\n\n" + DENY + "\n" ) );
        assertEquals( "DENY", e.violations().get( 0 ).shape() );
        assertEquals( "Open page.\n", stored() );
        assertFalse( locked() );
    }

    @Test
    void aNewPageWithADisputedRuleIsNotCreated() {
        assertThrows( AccessRuleShapeException.class, () -> engine.saveText( PAGE, DENY + "\n\nBody.\n" ) );
        assertNull( pages().getPage( PAGE ) );
    }

    @Test
    void anAdministratorSavesOtherEditsToALockedPageAndItStaysLocked() throws Exception {
        storeLockedPage( "Intro.\n\n" + DENY + "\n\nOld body.\n" );
        assertTrue( locked(), "precondition: the stored page is locked" );

        engine.saveText( PAGE, "Intro, reworded.\n\n" + DENY + "\n\nNew body.\n" );

        assertEquals( "Intro, reworded.\n\n" + DENY + "\n\nNew body.\n", stored() );
        assertTrue( locked(), "the disputed span is still there" );
    }

    /**
     * The frontmatter-rewriting save filters production wires when a datasource is configured, at their production
     * priorities and after the core filters, as {@code WikiEngine.initDataSourceSubsystems} registers them.
     */
    private void wireFrontmatterRewritingFilters() {
        final Properties props = new Properties();
        props.setProperty( StructuralSpinePageFilter.PROP_ENFORCEMENT_ENABLED, "true" );
        props.setProperty( FrontmatterDefaultsFilter.PROP_AUTO_DEFAULTS, "true" );
        final FilterManager filters = engine.getManager( FilterManager.class );
        filters.addPageFilter( new FrontmatterDefaultsFilter( name -> false, props ), -1004 );
        filters.addPageFilter( new StructuralSpinePageFilter( mock( StructuralIndexService.class ), name -> false, props ), -1003 );
    }

    /**
     * A YAML escape holds no {@code [&#123;} as typed, but the spine filter re-emits the frontmatter (it adds the missing
     * {@code canonical_id}) with the escape decoded: the stored text holds an {@code IN_FRONTMATTER} rule. The check
     * must read the text that is stored, after every filter that rewrites it.
     */
    @Test
    void aRuleAFrontmatterRewriteSpellsOutIsRefusedAndNothingIsWritten() throws Exception {
        wireFrontmatterRewritingFilters();
        engine.saveText( PAGE, "---\ntitle: Open\n---\nOpen page.\n" );
        final String before = stored();
        final String escaped = "---\ntitle: Open\nsummary: \"\\x5B{ALLOW view Admin}]\"\n---\nOpen page.\n";
        assertFalse( escaped.contains( "[{" ), "precondition: the text as typed holds no rule" );

        final AccessRuleShapeException e = assertThrows( AccessRuleShapeException.class, () -> engine.saveText( PAGE, escaped ) );

        assertEquals( "IN_FRONTMATTER", e.violations().get( 0 ).shape() );
        assertEquals( "summary", e.violations().get( 0 ).field() );
        assertEquals( before, stored() );
        assertFalse( locked() );
    }

    /** Whatever its priority, no filter runs after the check: a rule the last filter of all adds is refused too. */
    @Test
    void aRuleTheLastFilterOfTheChainAddsIsRefused() {
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String preSave( final Context context, final String content ) {
                return content + "\n" + DENY + "\n";
            }
        }, Integer.MIN_VALUE );

        assertThrows( AccessRuleShapeException.class, () -> engine.saveText( PAGE, "Open page.\n" ) );
        assertNull( pages().getPage( PAGE ) );
    }

    @Test
    void removingTheDisputedSpanUnlocksThePageOnTheNextCheck() throws Exception {
        storeLockedPage( DENY + "\n\nBody.\n" );
        assertTrue( locked(), "precondition: the stored page is locked" );

        engine.saveText( PAGE, "[{ALLOW view Bob}]\n\nBody.\n" );

        assertFalse( locked(), "a canonical rule in place of the disputed one unlocks the page" );
    }
}

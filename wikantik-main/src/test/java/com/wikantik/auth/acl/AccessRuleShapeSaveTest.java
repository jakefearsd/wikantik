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
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void removingTheDisputedSpanUnlocksThePageOnTheNextCheck() throws Exception {
        storeLockedPage( DENY + "\n\nBody.\n" );
        assertTrue( locked(), "precondition: the stored page is locked" );

        engine.saveText( PAGE, "[{ALLOW view Bob}]\n\nBody.\n" );

        assertFalse( locked(), "a canonical rule in place of the disputed one unlocks the page" );
    }
}

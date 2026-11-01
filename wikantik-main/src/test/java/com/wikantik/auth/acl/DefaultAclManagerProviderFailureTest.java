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
import com.wikantik.WikiSessionTest;
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.permissions.PermissionFactory;
import com.wikantik.providers.TextFailingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A page whose text the provider cannot read is locked — not opened, as reading it through the repository's
 * exception-swallowing {@code getPureText} (which answers "") would do — and the lock is not cached, so the page reads
 * normally once the provider recovers. A page whose text really is empty stays open.
 */
class DefaultAclManagerProviderFailureTest {

    private TestEngine engine;

    @BeforeEach
    void start() throws Exception {
        TextFailingProvider.TEXTS.clear();
        TextFailingProvider.FAILING.clear();
        TextFailingProvider.TEXTS.put( "FlakyPage", "[{ALLOW view Admin}]\nSecret.\n" );
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( "wikantik.pageProvider", TextFailingProvider.class.getName() );
        props.setProperty( "wikantik.ontology.enabled", "false" );
        props.setProperty( "wikantik.cache.enable", "false" );
        engine = new TestEngine( props );
    }

    @AfterEach
    void stop() {
        TextFailingProvider.TEXTS.clear();
        TextFailingProvider.FAILING.clear();
        engine.stop();
    }

    private Acl acl( final String name ) {
        return engine.getManager( AclManager.class ).getPermissions( engine.getManager( PageManager.class ).getPage( name ) );
    }

    @Test
    void aPageWhoseTextCannotBeReadIsLockedUntilItCanBe() throws Exception {
        TextFailingProvider.FAILING.add( "FlakyPage" );
        assertNotNull( acl( "FlakyPage" ).getAclEntry( DefaultAclManager.LOCKED_PRINCIPAL ), "fail closed, not open" );
        final Page page = engine.getManager( PageManager.class ).getPage( "FlakyPage" );
        assertFalse( engine.getManager( AuthorizationManager.class ).isPermitted( WikiSessionTest.anonymousSession( engine ),
                PermissionFactory.getPagePermission( page, "view" ) ), "anonymous cannot view a page whose rules cannot be read" );

        TextFailingProvider.FAILING.clear();
        final Acl recovered = acl( "FlakyPage" );
        assertNull( recovered.getAclEntry( DefaultAclManager.LOCKED_PRINCIPAL ), "the lock was not cached" );
        assertNotNull( recovered.getAclEntry( new WikiPrincipal( "Admin" ) ), "the page's own rule, once readable" );
    }

    @Test
    void aPageWithEmptyTextIsOpen() {
        assertTrue( acl( "EmptyPage" ).isEmpty() );
    }
}

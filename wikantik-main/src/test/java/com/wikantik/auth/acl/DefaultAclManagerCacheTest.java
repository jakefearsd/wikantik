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
import com.wikantik.api.core.Acl;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that {@link DefaultAclManager} caches parsed {@link Acl}s across
 * the {@code Page}-instance churn caused by the 60s page-cache TTL, keyed by
 * (name, version, lastModified), and correctly invalidates on a new version.
 */
public class DefaultAclManagerCacheTest {

    TestEngine m_engine = TestEngine.build();

    @BeforeEach
    public void setUp() throws Exception {
        m_engine.saveText( "TestDefaultPage", "Foo" );
    }

    @AfterEach
    public void tearDown() {
        try {
            m_engine.getManager( PageManager.class ).deletePage( "TestDefaultPage" );
            m_engine.getManager( PageManager.class ).deletePage( "AclCacheProbe" );
            m_engine.getManager( PageManager.class ).deletePage( "AclCacheProbe2" );
        } catch ( final ProviderException ignored ) {
            // test cleanup — page may not exist
        }
    }

    @Test
    void aclSurvivesPageInstanceChurn() throws Exception {
        m_engine.saveText( "AclCacheProbe", "[{ALLOW view Admin}]\n\nBody." );

        // Note: a Page fetched straight from PageManager already has its ACL
        // populated as a side effect of the render pass that saveText() triggers
        // (AccessRuleLinkNodePostProcessorState parses [{ALLOW ...}] wikilinks and
        // calls page.setAcl() during rendering) — so it would short-circuit
        // getPermissions() before ever reaching the cache under test. Build two
        // independent *detached* Page instances instead — same name/version/
        // lastModified as the stored page, but with getAcl() == null — which is
        // exactly what the 60s page-cache TTL hands getPermissions() on eviction
        // and reload: a fresh, unrendered Page instance for an unchanged version.
        final Page stored = m_engine.getManager( PageManager.class ).getPage( "AclCacheProbe" );
        final int version = stored.getVersion();
        final java.util.Date lastModified = stored.getLastModified();

        final Page first = Wiki.contents().page( m_engine, "AclCacheProbe" );
        first.setVersion( version );
        first.setLastModified( lastModified );
        final Acl acl1 = m_engine.getManager( AclManager.class ).getPermissions( first );

        final Page second = Wiki.contents().page( m_engine, "AclCacheProbe" );
        second.setVersion( version );
        second.setLastModified( lastModified );
        final Acl acl2 = m_engine.getManager( AclManager.class ).getPermissions( second );

        assertSame( acl1, acl2, "same page version must be served from the ACL cache, not re-extracted" );
    }

    @Test
    void newPageVersionInvalidatesCachedAcl() throws Exception {
        m_engine.saveText( "AclCacheProbe2", "[{ALLOW view Admin}]\n\nBody." );
        final Page stored1 = m_engine.getManager( PageManager.class ).getPage( "AclCacheProbe2" );
        final Page v1 = Wiki.contents().page( m_engine, "AclCacheProbe2" );
        v1.setVersion( stored1.getVersion() );
        v1.setLastModified( stored1.getLastModified() );
        final Acl acl1 = m_engine.getManager( AclManager.class ).getPermissions( v1 );

        m_engine.saveText( "AclCacheProbe2", "[{ALLOW view Admin,Authenticated}]\n\nBody v2." );
        final Page stored2 = m_engine.getManager( PageManager.class ).getPage( "AclCacheProbe2" );
        final Page v2 = Wiki.contents().page( m_engine, "AclCacheProbe2" );
        v2.setVersion( stored2.getVersion() );
        v2.setLastModified( stored2.getLastModified() );
        final Acl acl2 = m_engine.getManager( AclManager.class ).getPermissions( v2 );

        assertNotSame( acl1, acl2, "a new version must be re-extracted, not served stale" );
    }
}

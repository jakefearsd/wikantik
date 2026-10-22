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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.Audience;
import com.wikantik.api.pagegraph.Confidence;
import com.wikantik.api.pagegraph.Verification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code verificationOf} is answered from memory after a rebuild — no per-page query. */
@SuppressWarnings( { "unchecked", "rawtypes" } )
class DefaultStructuralIndexServiceVerificationCacheTest {

    private static final String ID_A = "01AAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String ID_B = "01BBBBBBBBBBBBBBBBBBBBBBBB";

    private PageManager pageManager;
    private PageCanonicalIdsDao dao;
    private PageVerificationDao verificationDao;
    private DefaultStructuralIndexService svc;

    private Page page( final String name, final String id, final String extraFm ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        when( p.getLastModified() ).thenReturn( new java.util.Date( 1700000000000L ) );
        when( pageManager.getPureText( p ) ).thenReturn(
                "---\ncanonical_id: " + id + "\ntitle: " + name + "\ntype: article\n" + extraFm + "---\nbody" );
        return p;
    }

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        dao = mock( PageCanonicalIdsDao.class );
        when( dao.upsert( anyString(), anyString(), anyString(), anyString(), any() ) )
                .thenReturn( PageCanonicalIdsDao.UpsertResult.WRITTEN );
        verificationDao = mock( PageVerificationDao.class );
        svc = new DefaultStructuralIndexService( pageManager, dao, verificationDao, null,
                new StructuralIndexMetrics() );
    }

    @Test
    void verificationOf_afterRebuild_issuesNoPerPageQueries() throws Exception {
        final Page a = page( "PageA", ID_A, "" );
        final Page b = page( "PageB", ID_B, "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a, b ) );
        final Verification va = new Verification( Instant.parse( "2026-01-01T00:00:00Z" ), "alice",
                Confidence.AUTHORITATIVE, Audience.HUMANS_AND_AGENTS );
        when( verificationDao.findAll() ).thenReturn( Map.of( ID_A, va ) );

        svc.rebuild();
        for ( int i = 0; i < 50; i++ ) {
            assertEquals( Optional.of( va ), svc.verificationOf( ID_A ) );
            assertEquals( Optional.empty(), svc.verificationOf( ID_B ) );
        }

        verify( verificationDao, never() ).findByCanonicalId( anyString() );
        verify( verificationDao, times( 1 ) ).findAll();
    }

    @Test
    void verificationOf_afterSave_seesTheNewStampImmediately() throws Exception {
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of() );
        when( verificationDao.findAll() ).thenReturn( Map.of() );
        svc.rebuild();
        assertTrue( svc.verificationOf( ID_A ).isEmpty() );

        final Page a = page( "PageA", ID_A,
                "verified_at: 2026-02-02T00:00:00Z\nverified_by: bob\nconfidence: authoritative\n" );
        when( pageManager.getPage( "PageA" ) ).thenReturn( a );
        svc.onPageSaved( "PageA" );

        final Verification v = svc.verificationOf( ID_A ).orElseThrow();
        assertEquals( "bob", v.verifiedBy() );
        assertEquals( Confidence.AUTHORITATIVE, v.confidence() );
        verify( verificationDao, never() ).findByCanonicalId( anyString() );
    }

    @Test
    void verificationOf_afterPageDeleted_isEmpty() throws Exception {
        final Page a = page( "PageA", ID_A, "" );
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of( a ) );
        when( verificationDao.findAll() ).thenReturn( Map.of( ID_A, Verification.unverified() ) );
        svc.rebuild();
        assertTrue( svc.verificationOf( ID_A ).isPresent() );

        svc.onPageDeleted( "PageA" );

        assertTrue( svc.verificationOf( ID_A ).isEmpty() );
    }

    @Test
    void verificationOf_whenBulkLoadFails_fallsBackToPerIdLookup() throws Exception {
        when( pageManager.getAllPages() ).thenReturn( (Collection) List.of() );
        when( verificationDao.findAll() ).thenReturn( null );
        final Verification v = Verification.unverified();
        when( verificationDao.findByCanonicalId( ID_A ) ).thenReturn( Optional.of( v ) );
        svc.rebuild();

        assertEquals( Optional.of( v ), svc.verificationOf( ID_A ) );
    }
}

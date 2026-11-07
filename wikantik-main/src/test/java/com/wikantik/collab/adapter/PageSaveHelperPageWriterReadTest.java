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
package com.wikantik.collab.adapter;

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOutcome;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.collab.PageReadException;
import com.wikantik.collab.PublishReadBackException;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.providers.NoSuchVersionException;
import com.wikantik.providers.RepositoryModifiedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fix round 1, items 1 and 2: the page reads distinguish present, absent and unknown. A provider error is never read as
 * "missing" (the orphan sweep would freeze, a retry would drop a rename) nor as an empty body (a settle would merge it
 * as an outside write emptying the draft's base). The engine's lenient reads ({@code getPage} answers null and
 * {@code getPureText} answers "" on a {@link ProviderException}) are stubbed the way the engine behaves, so a writer
 * built on them is caught.
 */
class PageSaveHelperPageWriterReadTest {

    private PageManager pm;
    private PageProvider provider;
    private PageSaveHelper helper;
    private PageSaveHelperPageWriter writer;
    private Page page;

    @BeforeEach
    void setUp() throws Exception {
        pm = mock( PageManager.class );
        provider = mock( PageProvider.class );
        when( pm.getProvider() ).thenReturn( provider );
        helper = mock( PageSaveHelper.class );
        writer = new PageSaveHelperPageWriter( pm, helper );
        page = mock( Page.class );
        when( page.getVersion() ).thenReturn( 3 );
        when( page.getAuthor() ).thenReturn( "bob" );
    }

    /** The engine's lenient read paths, as {@code DefaultPageRepository} answers them when the provider fails. */
    private void lenientReadsSwallowTheError() {
        when( pm.getPage( any() ) ).thenReturn( null );
        when( pm.getPage( any(), anyInt() ) ).thenReturn( null );
        when( pm.getPureText( any(), anyInt() ) ).thenReturn( "" );
    }

    @Test
    void aProviderErrorReadingThePageInfoThrowsInsteadOfReadingAsMissing() throws Exception {
        lenientReadsSwallowTheError();
        when( pm.getPageInfo( eq( "P" ), anyInt() ) ).thenThrow( new ProviderException( "disk gone" ) );
        when( provider.getPageInfoNoMetadata( eq( "P" ), anyInt() ) ).thenThrow( new ProviderException( "disk gone" ) );
        assertThrows( PageReadException.class, () -> writer.latest( "P" ) );
        assertThrows( PageReadException.class, () -> writer.version( "P", 2 ) );
        assertThrows( PageReadException.class, () -> writer.exists( "P" ) );
    }

    @Test
    void aProviderErrorReadingTheTextThrowsAndNeverYieldsAnEmptyBody() throws Exception {
        when( pm.getPage( any() ) ).thenReturn( page );
        when( pm.getPage( any(), anyInt() ) ).thenReturn( page );
        when( pm.getPureText( any(), anyInt() ) ).thenReturn( "" );   // what the engine answers on the error
        when( pm.getPageInfo( eq( "P" ), anyInt() ) ).thenReturn( page );
        when( pm.getPageText( eq( "P" ), anyInt() ) ).thenThrow( new ProviderException( "read failed" ) );
        assertThrows( PageReadException.class, () -> writer.latest( "P" ) );
        assertThrows( PageReadException.class, () -> writer.version( "P", 3 ) );
    }

    @Test
    void anExistingPageWhoseTextReadsAsNullThrows() throws Exception {
        when( pm.getPageInfo( eq( "P" ), anyInt() ) ).thenReturn( page );
        when( pm.getPageText( eq( "P" ), anyInt() ) ).thenReturn( null );
        assertThrows( PageReadException.class, () -> writer.latest( "P" ) );
    }

    @Test
    void absentPagesAndVersionsAreEmpty() throws Exception {
        when( pm.getPageInfo( eq( "Gone" ), anyInt() ) ).thenReturn( null );
        when( provider.getPageInfoNoMetadata( eq( "Gone" ), anyInt() ) ).thenReturn( null );
        assertEquals( Optional.empty(), writer.latest( "Gone" ) );
        assertFalse( writer.exists( "Gone" ) );
        when( pm.getPageInfo( eq( "P" ), eq( 9 ) ) ).thenReturn( page );
        when( pm.getPageText( eq( "P" ), eq( 9 ) ) ).thenThrow( new NoSuchVersionException( "deleted meanwhile" ) );
        assertEquals( Optional.empty(), writer.version( "P", 9 ) );
    }

    @Test
    void aReadableEmptyPageIsAnEmptyBodyNotAnError() throws Exception {
        when( pm.getPageInfo( eq( "P" ), anyInt() ) ).thenReturn( page );
        when( pm.getPageText( eq( "P" ), anyInt() ) ).thenReturn( "" );
        when( provider.getPageInfoNoMetadata( eq( "P" ), anyInt() ) ).thenReturn( page );
        assertEquals( "", writer.latest( "P" ).orElseThrow().body() );
        assertTrue( writer.exists( "P" ) );
    }

    /**
     * The page manager's {@code getPageInfo} runs {@code CachingProvider.refreshMetadata}, which reads and parses the text;
     * {@code exists()} asks the provider's metadata-free lookup, which throws on a provider error.
     */
    @Test
    void existsReadsOnlyTheMetadataFreePageInfo() throws Exception {
        when( provider.getPageInfoNoMetadata( eq( "P" ), eq( PageProvider.LATEST_VERSION ) ) ).thenReturn( page );
        when( pm.getPageInfo( any(), anyInt() ) ).thenThrow( new AssertionError( "exists() must not refresh metadata" ) );
        when( pm.getPageText( any(), anyInt() ) ).thenThrow( new AssertionError( "exists() must not read the text" ) );
        assertTrue( writer.exists( "P" ) );
    }

    @Test
    void existsRetriesOnceWhenTheRepositoryWasModifiedUnderIt() throws Exception {
        when( provider.getPageInfoNoMetadata( eq( "P" ), eq( PageProvider.LATEST_VERSION ) ) )
                .thenThrow( new RepositoryModifiedException( "changed on disk", "P" ) ).thenReturn( null );
        assertFalse( writer.exists( "P" ) );
    }

    /**
     * Follow-up 1: the latest state is the text of the version the page info named. Reading "latest" twice lets a save
     * land between the reads and yields version N with the text of N+1.
     */
    @Test
    void latestReadsTheTextOfTheVersionItsPageInfoNamed() throws Exception {
        when( pm.getPageInfo( eq( "P" ), eq( PageProvider.LATEST_VERSION ) ) ).thenReturn( page );   // version 3
        when( pm.getPageText( eq( "P" ), eq( 3 ) ) ).thenReturn( "three\n" );
        when( pm.getPageText( eq( "P" ), eq( PageProvider.LATEST_VERSION ) ) ).thenReturn( "four, saved meanwhile\n" );
        final var state = writer.latest( "P" ).orElseThrow();
        assertEquals( 3, state.version() );
        assertEquals( "three\n", state.body() );
    }

    @Test
    void aLatestPageWithoutAVersionNumberIsReadAsLatest() throws Exception {
        when( page.getVersion() ).thenReturn( PageProvider.LATEST_VERSION );   // a provider that keeps no versions
        when( pm.getPageInfo( eq( "P" ), eq( PageProvider.LATEST_VERSION ) ) ).thenReturn( page );
        when( pm.getPageText( eq( "P" ), eq( PageProvider.LATEST_VERSION ) ) ).thenReturn( "only\n" );
        assertEquals( "only\n", writer.latest( "P" ).orElseThrow().body() );
    }

    @Test
    void aPublishWhoseReadBackFailsThrowsInsteadOfReportingAnEmptyBody() throws Exception {
        when( helper.saveTracked( eq( "P" ), any(), any(), any() ) ).thenReturn( new SaveOutcome( page, 3, false ) );
        when( pm.getPureText( any(), anyInt() ) ).thenReturn( "" );
        when( pm.getPageText( eq( "P" ), anyInt() ) ).thenThrow( new ProviderException( "read failed" ) );
        final PublishRequest req = new PublishRequest( "P", "mine\n", Map.of(), 2, "alice", "note" );
        // a failed publish is claimed later by its text hash and author (E39); an empty body would be mapped as the save
        // filters' rewrite and wipe the draft
        final PublishReadBackException e = assertThrows( PublishReadBackException.class, () -> writer.publish( req ) );
        assertEquals( 3, e.savedVersion(), "the version the save wrote, so the session claims it as its own" );
    }
}

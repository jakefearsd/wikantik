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
package com.wikantik.providers;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.search.QueryItem;
import com.wikantik.api.search.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoggingPageProviderDecorator}.
 * <p>
 * Verifies that every method delegates to the wrapped provider and that
 * exceptions are propagated correctly.
 */
class LoggingPageProviderDecoratorTest {

    private PageProvider delegate;
    private LoggingPageProviderDecorator decorator;

    @BeforeEach
    void setUp() {
        delegate = mock( PageProvider.class );
        decorator = new LoggingPageProviderDecorator( delegate );
    }

    // --- initialize ---

    @Test
    void initializeDelegatesToProvider() throws NoRequiredPropertyException, IOException {
        final Engine engine = mock( Engine.class );
        final Properties props = new Properties();
        decorator.initialize( engine, props );
        verify( delegate ).initialize( engine, props );
    }

    @Test
    void initializePropagatesNoRequiredPropertyException() throws NoRequiredPropertyException, IOException {
        final Engine engine = mock( Engine.class );
        final Properties props = new Properties();
        doThrow( new NoRequiredPropertyException( "missing", "key" ) )
                .when( delegate ).initialize( engine, props );
        assertThrows( NoRequiredPropertyException.class,
                () -> decorator.initialize( engine, props ) );
    }

    @Test
    void initializePropagatesIOException() throws NoRequiredPropertyException, IOException {
        final Engine engine = mock( Engine.class );
        final Properties props = new Properties();
        doThrow( new IOException( "disk error" ) )
                .when( delegate ).initialize( engine, props );
        assertThrows( IOException.class,
                () -> decorator.initialize( engine, props ) );
    }

    // --- getProviderInfo ---

    @Test
    void getProviderInfoPrefixesWithLoggingDecoratorText() {
        when( delegate.getProviderInfo() ).thenReturn( "MyProvider v1" );
        final String info = decorator.getProviderInfo();
        assertTrue( info.contains( "MyProvider v1" ),
                "getProviderInfo should embed delegate info" );
        assertTrue( info.startsWith( "Logging decorator for:" ),
                "getProviderInfo should start with decorator prefix" );
    }

    // --- putPageText ---

    @Test
    void putPageTextDelegatesToProvider() throws ProviderException {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "Main" );
        decorator.putPageText( page, "content" );
        verify( delegate ).putPageText( page, "content" );
    }

    @Test
    void putPageTextPropagatesProviderException() throws ProviderException {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "Main" );
        doThrow( new ProviderException( "write failed" ) )
                .when( delegate ).putPageText( page, "content" );
        assertThrows( ProviderException.class,
                () -> decorator.putPageText( page, "content" ) );
    }

    @Test
    void putPageTextHandlesNullText() throws ProviderException {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "Main" );
        decorator.putPageText( page, null );
        verify( delegate ).putPageText( page, null );
    }

    // --- pageExists(String) ---

    @Test
    void pageExistsByNameDelegatesToProviderWhenTrue() {
        when( delegate.pageExists( "Main" ) ).thenReturn( true );
        assertTrue( decorator.pageExists( "Main" ) );
        verify( delegate ).pageExists( "Main" );
    }

    @Test
    void pageExistsByNameDelegatesToProviderWhenFalse() {
        when( delegate.pageExists( "Missing" ) ).thenReturn( false );
        assertFalse( decorator.pageExists( "Missing" ) );
        verify( delegate ).pageExists( "Missing" );
    }

    // --- pageExists(String, int) ---

    @Test
    void pageExistsByNameAndVersionDelegatesToProviderWhenTrue() {
        when( delegate.pageExists( "Main", 3 ) ).thenReturn( true );
        assertTrue( decorator.pageExists( "Main", 3 ) );
        verify( delegate ).pageExists( "Main", 3 );
    }

    @Test
    void pageExistsByNameAndVersionDelegatesToProviderWhenFalse() {
        when( delegate.pageExists( "Main", 99 ) ).thenReturn( false );
        assertFalse( decorator.pageExists( "Main", 99 ) );
        verify( delegate ).pageExists( "Main", 99 );
    }

    // --- findPages ---

    @Test
    void findPagesDelegatesToProvider() {
        final QueryItem[] query = new QueryItem[0];
        @SuppressWarnings( "unchecked" )
        final Collection<SearchResult> results = mock( Collection.class );
        when( delegate.findPages( query ) ).thenReturn( results );
        assertSame( results, decorator.findPages( query ) );
        verify( delegate ).findPages( query );
    }

    @Test
    void findPagesReturnsNullWhenDelegateReturnsNull() {
        when( delegate.findPages( null ) ).thenReturn( null );
        // null result should not throw and should be forwarded
        decorator.findPages( null );
        verify( delegate ).findPages( null );
    }

    // --- getPageInfo ---

    @Test
    void getPageInfoDelegatesToProvider() throws ProviderException {
        final Page page = mock( Page.class );
        when( delegate.getPageInfo( "Main", 1 ) ).thenReturn( page );
        assertSame( page, decorator.getPageInfo( "Main", 1 ) );
        verify( delegate ).getPageInfo( "Main", 1 );
    }

    @Test
    void getPageInfoPropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "info error" ) )
                .when( delegate ).getPageInfo( "Main", 1 );
        assertThrows( ProviderException.class,
                () -> decorator.getPageInfo( "Main", 1 ) );
    }

    // --- getAllPages ---

    @Test
    void getAllPagesDelegatesToProvider() throws ProviderException {
        @SuppressWarnings( "unchecked" )
        final Collection<Page> pages = mock( Collection.class );
        when( delegate.getAllPages() ).thenReturn( pages );
        assertSame( pages, decorator.getAllPages() );
        verify( delegate ).getAllPages();
    }

    @Test
    void getAllPagesPropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "all pages error" ) )
                .when( delegate ).getAllPages();
        assertThrows( ProviderException.class, () -> decorator.getAllPages() );
    }

    // --- getAllChangedSince ---

    @Test
    void getAllChangedSinceDelegatesToProvider() {
        final Date date = new Date( 0L );
        @SuppressWarnings( "unchecked" )
        final Collection<Page> pages = mock( Collection.class );
        when( delegate.getAllChangedSince( date ) ).thenReturn( pages );
        assertSame( pages, decorator.getAllChangedSince( date ) );
        verify( delegate ).getAllChangedSince( date );
    }

    // --- getPageCount ---

    @Test
    void getPageCountDelegatesToProvider() throws ProviderException {
        when( delegate.getPageCount() ).thenReturn( 42 );
        assertEquals( 42, decorator.getPageCount() );
        verify( delegate ).getPageCount();
    }

    @Test
    void getPageCountPropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "count error" ) )
                .when( delegate ).getPageCount();
        assertThrows( ProviderException.class, () -> decorator.getPageCount() );
    }

    // --- getVersionHistory ---

    @Test
    void getVersionHistoryDelegatesToProvider() throws ProviderException {
        @SuppressWarnings( "unchecked" )
        final List<Page> history = mock( List.class );
        when( delegate.getVersionHistory( "Main" ) ).thenReturn( history );
        assertSame( history, decorator.getVersionHistory( "Main" ) );
        verify( delegate ).getVersionHistory( "Main" );
    }

    @Test
    void getVersionHistoryPropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "history error" ) )
                .when( delegate ).getVersionHistory( "Main" );
        assertThrows( ProviderException.class,
                () -> decorator.getVersionHistory( "Main" ) );
    }

    // --- getPageText ---

    @Test
    void getPageTextDelegatesToProvider() throws ProviderException {
        when( delegate.getPageText( "Main", 1 ) ).thenReturn( "content" );
        assertEquals( "content", decorator.getPageText( "Main", 1 ) );
        verify( delegate ).getPageText( "Main", 1 );
    }

    @Test
    void getPageTextPropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "text error" ) )
                .when( delegate ).getPageText( "Main", 1 );
        assertThrows( ProviderException.class,
                () -> decorator.getPageText( "Main", 1 ) );
    }

    @Test
    void getPageTextReturnsNullWhenDelegateReturnsNull() throws ProviderException {
        when( delegate.getPageText( "Main", -1 ) ).thenReturn( null );
        assertEquals( null, decorator.getPageText( "Main", -1 ) );
    }

    // --- deleteVersion ---

    @Test
    void deleteVersionDelegatesToProvider() throws ProviderException {
        decorator.deleteVersion( "Main", 2 );
        verify( delegate ).deleteVersion( "Main", 2 );
    }

    @Test
    void deleteVersionPropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "delete version error" ) )
                .when( delegate ).deleteVersion( "Main", 2 );
        assertThrows( ProviderException.class,
                () -> decorator.deleteVersion( "Main", 2 ) );
    }

    // --- deletePage ---

    @Test
    void deletePageDelegatesToProvider() throws ProviderException {
        decorator.deletePage( "Main" );
        verify( delegate ).deletePage( "Main" );
    }

    @Test
    void deletePagePropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "delete page error" ) )
                .when( delegate ).deletePage( "Main" );
        assertThrows( ProviderException.class,
                () -> decorator.deletePage( "Main" ) );
    }

    // --- movePage ---

    @Test
    void movePageDelegatesToProvider() throws ProviderException {
        decorator.movePage( "OldName", "NewName" );
        verify( delegate ).movePage( "OldName", "NewName" );
    }

    @Test
    void movePagePropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "move error" ) )
                .when( delegate ).movePage( "OldName", "NewName" );
        assertThrows( ProviderException.class,
                () -> decorator.movePage( "OldName", "NewName" ) );
    }
}

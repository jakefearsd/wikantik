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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-mockito unit tests for {@link PageProviderDecorator} that cover the
 * delegation paths not exercised by the integration-style
 * {@link PageProviderDecoratorTest}.
 */
class PageProviderDecoratorCITest {

    private PageProvider delegate;
    /** Concrete anonymous subclass used to exercise the abstract base class. */
    private PageProviderDecorator decorator;

    @BeforeEach
    void setUp() {
        delegate = mock( PageProvider.class );
        decorator = new PageProviderDecorator( delegate ) {};
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
        doThrow( new NoRequiredPropertyException( "missing", "aKey" ) )
                .when( delegate ).initialize( engine, props );
        assertThrows( NoRequiredPropertyException.class,
                () -> decorator.initialize( engine, props ) );
    }

    @Test
    void initializePropagatesIOException() throws NoRequiredPropertyException, IOException {
        final Engine engine = mock( Engine.class );
        final Properties props = new Properties();
        doThrow( new IOException( "io error" ) )
                .when( delegate ).initialize( engine, props );
        assertThrows( IOException.class,
                () -> decorator.initialize( engine, props ) );
    }

    // --- putPageText ---

    @Test
    void putPageTextDelegatesToProvider() throws ProviderException {
        final Page page = mock( Page.class );
        decorator.putPageText( page, "hello" );
        verify( delegate ).putPageText( page, "hello" );
    }

    @Test
    void putPageTextPropagatesProviderException() throws ProviderException {
        final Page page = mock( Page.class );
        doThrow( new ProviderException( "write fail" ) )
                .when( delegate ).putPageText( page, "hello" );
        assertThrows( ProviderException.class,
                () -> decorator.putPageText( page, "hello" ) );
    }

    // --- pageExists(String, int) ---

    @Test
    void pageExistsByVersionDelegatesToProvider() {
        when( delegate.pageExists( "Main", 2 ) ).thenReturn( true );
        assertTrue( decorator.pageExists( "Main", 2 ) );
        verify( delegate ).pageExists( "Main", 2 );
    }

    @Test
    void pageExistsByVersionReturnsFalseWhenDelegateReturnsFalse() {
        when( delegate.pageExists( "Main", 99 ) ).thenReturn( false );
        assertFalse( decorator.pageExists( "Main", 99 ) );
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
        doThrow( new ProviderException( "all pages fail" ) )
                .when( delegate ).getAllPages();
        assertThrows( ProviderException.class, () -> decorator.getAllPages() );
    }

    // --- getAllChangedSince ---

    @Test
    void getAllChangedSinceDelegatesToProvider() {
        final Date date = new Date( 1_000_000L );
        @SuppressWarnings( "unchecked" )
        final Collection<Page> pages = mock( Collection.class );
        when( delegate.getAllChangedSince( date ) ).thenReturn( pages );
        assertSame( pages, decorator.getAllChangedSince( date ) );
        verify( delegate ).getAllChangedSince( date );
    }

    // --- deleteVersion ---

    @Test
    void deleteVersionDelegatesToProvider() throws ProviderException {
        decorator.deleteVersion( "Main", 3 );
        verify( delegate ).deleteVersion( "Main", 3 );
    }

    @Test
    void deleteVersionPropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "delete version fail" ) )
                .when( delegate ).deleteVersion( "Main", 3 );
        assertThrows( ProviderException.class,
                () -> decorator.deleteVersion( "Main", 3 ) );
    }

    // --- deletePage ---

    @Test
    void deletePageDelegatesToProvider() throws ProviderException {
        decorator.deletePage( "Main" );
        verify( delegate ).deletePage( "Main" );
    }

    @Test
    void deletePagePropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "delete page fail" ) )
                .when( delegate ).deletePage( "Main" );
        assertThrows( ProviderException.class,
                () -> decorator.deletePage( "Main" ) );
    }

    // --- movePage ---

    @Test
    void movePageDelegatesToProvider() throws ProviderException {
        decorator.movePage( "OldPage", "NewPage" );
        verify( delegate ).movePage( "OldPage", "NewPage" );
    }

    @Test
    void movePagePropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "move fail" ) )
                .when( delegate ).movePage( "OldPage", "NewPage" );
        assertThrows( ProviderException.class,
                () -> decorator.movePage( "OldPage", "NewPage" ) );
    }

    // --- getDelegate / getRealProvider ---

    @Test
    void getDelegateReturnsDirectDelegate() {
        assertSame( delegate, decorator.getDelegate() );
    }

    @Test
    void getRealProviderReturnsDelegateWhenNotADecorator() {
        assertSame( delegate, decorator.getRealProvider() );
    }

    @Test
    void getRealProviderUnwrapsSingleDecoratorLevel() {
        final PageProviderDecorator outer = new PageProviderDecorator( decorator ) {};
        assertSame( delegate, outer.getRealProvider() );
    }

    @Test
    void getRealProviderUnwrapsMultipleLevels() {
        final PageProviderDecorator level2 = new PageProviderDecorator( decorator ) {};
        final PageProviderDecorator level3 = new PageProviderDecorator( level2 ) {};
        assertSame( delegate, level3.getRealProvider() );
    }

    // --- getPageCount ---

    @Test
    void getPageCountPropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "count fail" ) )
                .when( delegate ).getPageCount();
        assertThrows( ProviderException.class, () -> decorator.getPageCount() );
    }

    // --- getVersionHistory ---

    @Test
    void getVersionHistoryDelegatesToProvider() throws ProviderException {
        @SuppressWarnings( "unchecked" )
        final List<Page> history = mock( List.class );
        when( delegate.getVersionHistory( "A" ) ).thenReturn( history );
        assertSame( history, decorator.getVersionHistory( "A" ) );
        verify( delegate ).getVersionHistory( "A" );
    }

    @Test
    void getVersionHistoryPropagatesProviderException() throws ProviderException {
        doThrow( new ProviderException( "history fail" ) )
                .when( delegate ).getVersionHistory( "A" );
        assertThrows( ProviderException.class,
                () -> decorator.getVersionHistory( "A" ) );
    }
}

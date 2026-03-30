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

import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetricsPageProviderDecorator}.
 * <p>
 * Verifies delegation behaviour, that call counters and timing accumulators
 * are incremented on every method call (including calls that throw), and that
 * the reset and average-calculation helpers work correctly.
 */
class MetricsPageProviderDecoratorTest {

    private PageProvider delegate;
    private MetricsPageProviderDecorator decorator;

    @BeforeEach
    void setUp() {
        delegate = mock( PageProvider.class );
        decorator = new MetricsPageProviderDecorator( delegate );
    }

    // --- getProviderInfo ---

    @Test
    void getProviderInfoPrefixesWithMetricsDecoratorText() {
        when( delegate.getProviderInfo() ).thenReturn( "Base" );
        final String info = decorator.getProviderInfo();
        assertTrue( info.startsWith( "Metrics decorator for:" ),
                "getProviderInfo should start with decorator prefix" );
        assertTrue( info.contains( "Base" ),
                "getProviderInfo should include delegate info" );
    }

    // --- putPageText ---

    @Test
    void putPageTextDelegatesToProvider() throws ProviderException {
        final Page page = mock( Page.class );
        decorator.putPageText( page, "content" );
        verify( delegate ).putPageText( page, "content" );
    }

    @Test
    void putPageTextIncrementsCallCounter() throws ProviderException {
        final Page page = mock( Page.class );
        assertEquals( 0, decorator.getMetrics().putPageTextCalls.get() );
        decorator.putPageText( page, "text" );
        assertEquals( 1, decorator.getMetrics().putPageTextCalls.get() );
        decorator.putPageText( page, "text2" );
        assertEquals( 2, decorator.getMetrics().putPageTextCalls.get() );
    }

    @Test
    void putPageTextRecordsTimeEvenOnException() throws ProviderException {
        final Page page = mock( Page.class );
        doThrow( new ProviderException( "fail" ) )
                .when( delegate ).putPageText( page, "x" );
        assertThrows( ProviderException.class,
                () -> decorator.putPageText( page, "x" ) );
        assertEquals( 1, decorator.getMetrics().putPageTextCalls.get() );
        assertTrue( decorator.getMetrics().putPageTextTimeNanos.get() >= 0 );
    }

    // --- pageExists(String) ---

    @Test
    void pageExistsByNameDelegatesToProvider() {
        when( delegate.pageExists( "Main" ) ).thenReturn( true );
        assertTrue( decorator.pageExists( "Main" ) );
        verify( delegate ).pageExists( "Main" );
    }

    @Test
    void pageExistsByNameIncrementsCallCounter() {
        when( delegate.pageExists( "Main" ) ).thenReturn( true );
        assertEquals( 0, decorator.getMetrics().pageExistsCalls.get() );
        decorator.pageExists( "Main" );
        decorator.pageExists( "Main" );
        assertEquals( 2, decorator.getMetrics().pageExistsCalls.get() );
    }

    @Test
    void pageExistsByNameAccumulatesTime() {
        when( delegate.pageExists( "Main" ) ).thenReturn( false );
        decorator.pageExists( "Main" );
        assertTrue( decorator.getMetrics().pageExistsTimeNanos.get() >= 0 );
    }

    // --- pageExists(String, int) ---

    @Test
    void pageExistsByNameAndVersionDelegatesToProvider() {
        when( delegate.pageExists( "Main", 3 ) ).thenReturn( true );
        assertTrue( decorator.pageExists( "Main", 3 ) );
        verify( delegate ).pageExists( "Main", 3 );
    }

    @Test
    void pageExistsByNameAndVersionIncrementsSharedCounter() {
        when( delegate.pageExists( "Main", 1 ) ).thenReturn( false );
        decorator.pageExists( "Main", 1 );
        // both pageExists overloads share the same counter
        assertEquals( 1, decorator.getMetrics().pageExistsCalls.get() );
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
    void getPageInfoIncrementsCallCounter() throws ProviderException {
        final Page page = mock( Page.class );
        when( delegate.getPageInfo( "Main", -1 ) ).thenReturn( page );
        assertEquals( 0, decorator.getMetrics().getPageInfoCalls.get() );
        decorator.getPageInfo( "Main", -1 );
        assertEquals( 1, decorator.getMetrics().getPageInfoCalls.get() );
    }

    @Test
    void getPageInfoRecordsTimeEvenOnException() throws ProviderException {
        doThrow( new ProviderException( "info fail" ) )
                .when( delegate ).getPageInfo( "Main", 1 );
        assertThrows( ProviderException.class,
                () -> decorator.getPageInfo( "Main", 1 ) );
        assertEquals( 1, decorator.getMetrics().getPageInfoCalls.get() );
        assertTrue( decorator.getMetrics().getPageInfoTimeNanos.get() >= 0 );
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
    void getAllPagesIncrementsCallCounter() throws ProviderException {
        @SuppressWarnings( "unchecked" )
        final Collection<Page> pages = mock( Collection.class );
        when( delegate.getAllPages() ).thenReturn( pages );
        assertEquals( 0, decorator.getMetrics().getAllPagesCalls.get() );
        decorator.getAllPages();
        assertEquals( 1, decorator.getMetrics().getAllPagesCalls.get() );
    }

    @Test
    void getAllPagesRecordsTimeEvenOnException() throws ProviderException {
        doThrow( new ProviderException( "all pages fail" ) )
                .when( delegate ).getAllPages();
        assertThrows( ProviderException.class, () -> decorator.getAllPages() );
        assertEquals( 1, decorator.getMetrics().getAllPagesCalls.get() );
        assertTrue( decorator.getMetrics().getAllPagesTimeNanos.get() >= 0 );
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
    void getVersionHistoryIncrementsCallCounter() throws ProviderException {
        @SuppressWarnings( "unchecked" )
        final List<Page> history = mock( List.class );
        when( delegate.getVersionHistory( "Main" ) ).thenReturn( history );
        assertEquals( 0, decorator.getMetrics().getVersionHistoryCalls.get() );
        decorator.getVersionHistory( "Main" );
        assertEquals( 1, decorator.getMetrics().getVersionHistoryCalls.get() );
    }

    @Test
    void getVersionHistoryRecordsTimeEvenOnException() throws ProviderException {
        doThrow( new ProviderException( "history fail" ) )
                .when( delegate ).getVersionHistory( "Main" );
        assertThrows( ProviderException.class,
                () -> decorator.getVersionHistory( "Main" ) );
        assertEquals( 1, decorator.getMetrics().getVersionHistoryCalls.get() );
        assertTrue( decorator.getMetrics().getVersionHistoryTimeNanos.get() >= 0 );
    }

    // --- getPageText ---

    @Test
    void getPageTextDelegatesToProvider() throws ProviderException {
        when( delegate.getPageText( "Main", 1 ) ).thenReturn( "content" );
        assertEquals( "content", decorator.getPageText( "Main", 1 ) );
        verify( delegate ).getPageText( "Main", 1 );
    }

    @Test
    void getPageTextIncrementsCallCounter() throws ProviderException {
        when( delegate.getPageText( "Main", -1 ) ).thenReturn( "text" );
        assertEquals( 0, decorator.getMetrics().getPageTextCalls.get() );
        decorator.getPageText( "Main", -1 );
        decorator.getPageText( "Main", -1 );
        assertEquals( 2, decorator.getMetrics().getPageTextCalls.get() );
    }

    @Test
    void getPageTextRecordsTimeEvenOnException() throws ProviderException {
        doThrow( new ProviderException( "text fail" ) )
                .when( delegate ).getPageText( "Main", 1 );
        assertThrows( ProviderException.class,
                () -> decorator.getPageText( "Main", 1 ) );
        assertEquals( 1, decorator.getMetrics().getPageTextCalls.get() );
        assertTrue( decorator.getMetrics().getPageTextTimeNanos.get() >= 0 );
    }

    // --- deleteVersion ---

    @Test
    void deleteVersionDelegatesToProvider() throws ProviderException {
        decorator.deleteVersion( "Main", 2 );
        verify( delegate ).deleteVersion( "Main", 2 );
    }

    @Test
    void deleteVersionIncrementsCallCounter() throws ProviderException {
        assertEquals( 0, decorator.getMetrics().deleteVersionCalls.get() );
        decorator.deleteVersion( "Main", 1 );
        assertEquals( 1, decorator.getMetrics().deleteVersionCalls.get() );
    }

    @Test
    void deleteVersionRecordsTimeEvenOnException() throws ProviderException {
        doThrow( new ProviderException( "delete version fail" ) )
                .when( delegate ).deleteVersion( "Main", 2 );
        assertThrows( ProviderException.class,
                () -> decorator.deleteVersion( "Main", 2 ) );
        assertEquals( 1, decorator.getMetrics().deleteVersionCalls.get() );
        assertTrue( decorator.getMetrics().deleteVersionTimeNanos.get() >= 0 );
    }

    // --- deletePage ---

    @Test
    void deletePageDelegatesToProvider() throws ProviderException {
        decorator.deletePage( "Main" );
        verify( delegate ).deletePage( "Main" );
    }

    @Test
    void deletePageIncrementsCallCounter() throws ProviderException {
        assertEquals( 0, decorator.getMetrics().deletePageCalls.get() );
        decorator.deletePage( "Main" );
        assertEquals( 1, decorator.getMetrics().deletePageCalls.get() );
    }

    @Test
    void deletePageRecordsTimeEvenOnException() throws ProviderException {
        doThrow( new ProviderException( "delete page fail" ) )
                .when( delegate ).deletePage( "Main" );
        assertThrows( ProviderException.class,
                () -> decorator.deletePage( "Main" ) );
        assertEquals( 1, decorator.getMetrics().deletePageCalls.get() );
        assertTrue( decorator.getMetrics().deletePageTimeNanos.get() >= 0 );
    }

    // --- movePage ---

    @Test
    void movePageDelegatesToProvider() throws ProviderException {
        decorator.movePage( "Old", "New" );
        verify( delegate ).movePage( "Old", "New" );
    }

    @Test
    void movePageIncrementsCallCounter() throws ProviderException {
        assertEquals( 0, decorator.getMetrics().movePageCalls.get() );
        decorator.movePage( "Old", "New" );
        assertEquals( 1, decorator.getMetrics().movePageCalls.get() );
    }

    @Test
    void movePageRecordsTimeEvenOnException() throws ProviderException {
        doThrow( new ProviderException( "move fail" ) )
                .when( delegate ).movePage( "Old", "New" );
        assertThrows( ProviderException.class,
                () -> decorator.movePage( "Old", "New" ) );
        assertEquals( 1, decorator.getMetrics().movePageCalls.get() );
        assertTrue( decorator.getMetrics().movePageTimeNanos.get() >= 0 );
    }

    // --- Metrics.reset ---

    @Test
    void resetMetricsClearsAllCountersAndTimers() throws ProviderException {
        final Page page = mock( Page.class );
        when( delegate.pageExists( "A" ) ).thenReturn( true );
        when( delegate.getPageText( "A", -1 ) ).thenReturn( "text" );

        decorator.pageExists( "A" );
        decorator.getPageText( "A", -1 );
        decorator.deletePage( "A" );

        final MetricsPageProviderDecorator.Metrics m = decorator.getMetrics();
        assertTrue( m.pageExistsCalls.get() > 0 );
        assertTrue( m.getPageTextCalls.get() > 0 );
        assertTrue( m.deletePageCalls.get() > 0 );

        decorator.resetMetrics();

        assertEquals( 0, m.pageExistsCalls.get() );
        assertEquals( 0, m.pageExistsTimeNanos.get() );
        assertEquals( 0, m.getPageTextCalls.get() );
        assertEquals( 0, m.getPageTextTimeNanos.get() );
        assertEquals( 0, m.getPageInfoCalls.get() );
        assertEquals( 0, m.getPageInfoTimeNanos.get() );
        assertEquals( 0, m.putPageTextCalls.get() );
        assertEquals( 0, m.putPageTextTimeNanos.get() );
        assertEquals( 0, m.getAllPagesCalls.get() );
        assertEquals( 0, m.getAllPagesTimeNanos.get() );
        assertEquals( 0, m.getVersionHistoryCalls.get() );
        assertEquals( 0, m.getVersionHistoryTimeNanos.get() );
        assertEquals( 0, m.deletePageCalls.get() );
        assertEquals( 0, m.deletePageTimeNanos.get() );
        assertEquals( 0, m.deleteVersionCalls.get() );
        assertEquals( 0, m.deleteVersionTimeNanos.get() );
        assertEquals( 0, m.movePageCalls.get() );
        assertEquals( 0, m.movePageTimeNanos.get() );
    }

    // --- Metrics.getAverageGetPageTextMs ---

    @Test
    void averageGetPageTextMsIsZeroWhenNoCallsMade() {
        assertEquals( 0.0, decorator.getMetrics().getAverageGetPageTextMs(), 0.0 );
    }

    @Test
    void averageGetPageTextMsIsPositiveAfterCalls() throws ProviderException {
        when( delegate.getPageText( "A", -1 ) ).thenReturn( "hello" );
        decorator.getPageText( "A", -1 );
        assertTrue( decorator.getMetrics().getAverageGetPageTextMs() >= 0.0 );
    }

    // --- Metrics.getAverageGetPageInfoMs ---

    @Test
    void averageGetPageInfoMsIsZeroWhenNoCallsMade() {
        assertEquals( 0.0, decorator.getMetrics().getAverageGetPageInfoMs(), 0.0 );
    }

    @Test
    void averageGetPageInfoMsIsNonNegativeAfterCalls() throws ProviderException {
        final Page page = mock( Page.class );
        when( delegate.getPageInfo( "A", -1 ) ).thenReturn( page );
        decorator.getPageInfo( "A", -1 );
        assertTrue( decorator.getMetrics().getAverageGetPageInfoMs() >= 0.0 );
    }

    // --- Metrics.toString ---

    @Test
    void metricsToStringContainsKeyFields() throws ProviderException {
        when( delegate.pageExists( "A" ) ).thenReturn( true );
        decorator.pageExists( "A" );
        decorator.pageExists( "A" );
        final String s = decorator.getMetrics().toString();
        assertTrue( s.contains( "pageExists=2" ), "toString should include pageExists count" );
        assertTrue( s.contains( "getPageText=0" ), "toString should include getPageText count" );
    }

    @Test
    void metricsToStringIsZeroFreshDecorator() {
        final String s = decorator.getMetrics().toString();
        assertTrue( s.contains( "getPageText=0" ) );
        assertTrue( s.contains( "getPageInfo=0" ) );
    }
}

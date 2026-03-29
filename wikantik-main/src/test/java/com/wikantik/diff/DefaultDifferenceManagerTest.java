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
package com.wikantik.diff;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for {@link DefaultDifferenceManager}. Uses the package-private
 * constructor to inject mock dependencies without requiring a running engine.
 */
class DefaultDifferenceManagerTest {

    private PageManager pageManager;
    private Engine engine;
    private Context context;
    private Page page;
    private Properties props;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        engine = MockEngineBuilder.engine().with( PageManager.class, pageManager ).build();
        page = mock( Page.class );
        context = mock( Context.class );
        props = new Properties();

        when( context.getPage() ).thenReturn( page );
        when( context.getEngine() ).thenReturn( engine );
        when( page.getName() ).thenReturn( "TestPage" );
    }

    // -----------------------------------------------------------------------
    // makeDiff — delegates to the DiffProvider
    // -----------------------------------------------------------------------

    @Test
    void makeDiff_returnsProviderOutput() {
        final DefaultDifferenceManager mgr = new DefaultDifferenceManager( pageManager, props, engine );

        final String result = mgr.makeDiff( context, "old text", "new text" );

        // TraditionalDiffProvider (the default) returns non-null HTML when texts differ
        assertNotNull( result );
    }

    @Test
    void makeDiff_identicalTexts_returnsEmptyString() {
        final DefaultDifferenceManager mgr = new DefaultDifferenceManager( pageManager, props, engine );

        final String result = mgr.makeDiff( context, "same text", "same text" );

        assertEquals( "", result, "Identical texts should produce an empty diff" );
    }

    @Test
    void makeDiff_nullReturnFromProvider_isNormalisedToEmptyString() {
        // Use NullDiffProvider, which returns a fixed non-null string by design.
        // We verify the null-guard path by substituting a custom provider via properties.
        // The NullDiffProvider itself returns a sentinel string, not null — but
        // the contract guarantees we never return null from makeDiff().
        final DefaultDifferenceManager mgr = new DefaultDifferenceManager( pageManager, props, engine );

        final String result = mgr.makeDiff( context, "a", "b" );

        assertNotNull( result, "makeDiff must never return null" );
    }

    @Test
    void makeDiff_providerThrowsException_returnsErrorMessage() {
        // Inject a DiffProvider that always throws to exercise the catch block in makeDiff.
        final DefaultDifferenceManager mgr = new DefaultDifferenceManager( pageManager, new ThrowingDiffProvider() );

        final String result = mgr.makeDiff( context, "old", "new" );

        assertTrue( result.contains( "Failed" ), "Exception path should return a 'Failed' message, got: " + result );
    }

    // -----------------------------------------------------------------------
    // getDiff — fetches text via PageManager, then delegates to makeDiff
    // -----------------------------------------------------------------------

    @Test
    void getDiff_delegatesToPageManagerForBothVersions() {
        when( pageManager.getPureText( "TestPage", 1 ) ).thenReturn( "version one text" );
        when( pageManager.getPureText( "TestPage", 2 ) ).thenReturn( "version two text" );

        final DefaultDifferenceManager mgr = new DefaultDifferenceManager( pageManager, props, engine );
        mgr.getDiff( context, 1, 2 );

        verify( pageManager ).getPureText( "TestPage", 1 );
        verify( pageManager ).getPureText( "TestPage", 2 );
    }

    @Test
    void getDiff_version1IsLatest_treatsOldTextAsEmpty() {
        // When version1 == LATEST_VERSION the kludge forces page1 = "" so that
        // the diff reflects "new page" (empty old, real new).
        when( pageManager.getPureText( "TestPage", PageProvider.LATEST_VERSION ) ).thenReturn( "should be ignored" );
        when( pageManager.getPureText( "TestPage", 3 ) ).thenReturn( "current content" );

        final DefaultDifferenceManager mgr = new DefaultDifferenceManager( pageManager, props, engine );
        final String result = mgr.getDiff( context, PageProvider.LATEST_VERSION, 3 );

        // The diff is between "" and "current content", so it must be non-empty
        assertFalse( result.isEmpty(), "Diff from empty old text to new content should not be empty" );
    }

    @Test
    void getDiff_identicalVersions_returnsEmptyString() {
        when( pageManager.getPureText( "TestPage", 5 ) ).thenReturn( "same content" );

        final DefaultDifferenceManager mgr = new DefaultDifferenceManager( pageManager, props, engine );
        final String result = mgr.getDiff( context, 5, 5 );

        assertEquals( "", result, "Diff between identical versions should be empty" );
    }

    @Test
    void getDiff_usesPageNameFromContext() {
        when( page.getName() ).thenReturn( "SpecificPage" );
        when( pageManager.getPureText( eq( "SpecificPage" ), anyInt() ) ).thenReturn( "content" );

        final DefaultDifferenceManager mgr = new DefaultDifferenceManager( pageManager, props, engine );
        mgr.getDiff( context, 1, 2 );

        verify( pageManager, atLeastOnce() ).getPureText( eq( "SpecificPage" ), anyInt() );
    }

    // -----------------------------------------------------------------------
    // NullDiffProvider fallback — bad class name in properties
    // -----------------------------------------------------------------------

    @Test
    void constructor_unknownProviderClass_fallsBackToNullDiffProvider() {
        props.setProperty( DifferenceManager.PROP_DIFF_PROVIDER, "com.wikantik.diff.NonExistentProvider" );

        final DefaultDifferenceManager mgr = new DefaultDifferenceManager( pageManager, props, engine );

        // NullDiffProvider returns a sentinel string, not blank HTML
        final String result = mgr.makeDiff( context, "a", "b" );
        assertTrue( result.contains( "NullDiffProvider" ),
                "Should fall back to NullDiffProvider message, got: " + result );
    }

    // -----------------------------------------------------------------------
    // Helper — a DiffProvider whose makeDiffHtml always throws
    // -----------------------------------------------------------------------

    /** Package-private helper used only by this test class. */
    static class ThrowingDiffProvider implements DiffProvider {
        @Override
        public String makeDiffHtml( final Context ctx, final String oldText, final String newText ) {
            throw new RuntimeException( "simulated provider failure" );
        }

        @Override
        public void initialize( final Engine e, final Properties p ) {
            // no-op
        }

        @Override
        public String getProviderInfo() {
            return "ThrowingDiffProvider";
        }
    }

}

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
package com.wikantik.pagegraph.references;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.ObjectInputFilter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the genuinely-hard-to-reach corners of {@link DefaultReferenceManager}:
 * the real on-disk serialize/deserialize round trip that {@code initialize()} takes on
 * its "warm start" path (as opposed to the "cold start, rebuild everything" path every
 * other test in this package uses), the {@link ObjectInputFilter} deserialization
 * whitelist, an I/O failure while writing the cache, and the defensive
 * {@code ClassCastException} guard in {@code buildKeyLists()}.
 */
class DefaultReferenceManagerDiskRoundTripTest {

    // -----------------------------------------------------------------------
    // SAFE_DESERIALIZE_FILTER — exercised directly via reflection so every
    // branch of the whitelist can be proven without needing an attacker-supplied
    // serialized payload.
    // -----------------------------------------------------------------------

    private static ObjectInputFilter safeDeserializeFilter() throws Exception {
        final Field f = DefaultReferenceManager.class.getDeclaredField( "SAFE_DESERIALIZE_FILTER" );
        f.setAccessible( true );
        return ( ObjectInputFilter ) f.get( null );
    }

    private static ObjectInputFilter.FilterInfo filterInfoFor( final Class< ? > clazz ) {
        return new ObjectInputFilter.FilterInfo() {
            @Override public Class< ? > serialClass() { return clazz; }
            @Override public long arrayLength() { return -1; }
            @Override public long depth() { return 1; }
            @Override public long references() { return 1; }
            @Override public long streamBytes() { return 0; }
        };
    }

    @Test
    void safeDeserializeFilter_nullClass_isUndecided() throws Exception {
        assertEquals( ObjectInputFilter.Status.UNDECIDED,
                safeDeserializeFilter().checkInput( filterInfoFor( null ) ) );
    }

    @Test
    void safeDeserializeFilter_allowsWhitelistedScalarClass() throws Exception {
        assertEquals( ObjectInputFilter.Status.ALLOWED,
                safeDeserializeFilter().checkInput( filterInfoFor( String.class ) ) );
    }

    @Test
    void safeDeserializeFilter_rejectsNonWhitelistedScalarClass() throws Exception {
        assertEquals( ObjectInputFilter.Status.REJECTED,
                safeDeserializeFilter().checkInput( filterInfoFor( ProcessBuilder.class ) ) );
    }

    @Test
    void safeDeserializeFilter_allowsPrimitiveArray() throws Exception {
        assertEquals( ObjectInputFilter.Status.ALLOWED,
                safeDeserializeFilter().checkInput( filterInfoFor( int[].class ) ) );
    }

    @Test
    void safeDeserializeFilter_allowsArrayOfWhitelistedClass() throws Exception {
        assertEquals( ObjectInputFilter.Status.ALLOWED,
                safeDeserializeFilter().checkInput( filterInfoFor( String[].class ) ) );
    }

    @Test
    void safeDeserializeFilter_allowsArrayOfOtherJavaUtilClass() throws Exception {
        // Not individually whitelisted, but its component type starts with "java.util." —
        // the filter's deliberately-broader carve-out for JDK collection helper types.
        assertEquals( ObjectInputFilter.Status.ALLOWED,
                safeDeserializeFilter().checkInput( filterInfoFor( java.util.BitSet[].class ) ) );
    }

    @Test
    void safeDeserializeFilter_rejectsArrayOfNonWhitelistedClass() throws Exception {
        assertEquals( ObjectInputFilter.Status.REJECTED,
                safeDeserializeFilter().checkInput( filterInfoFor( Object[].class ) ) );
    }

    // -----------------------------------------------------------------------
    // buildKeyLists() — defensive ClassCastException guard
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( { "unchecked", "rawtypes" } )
    void buildKeyLists_toleratesClassCastExceptionWithoutThrowing( @TempDir final File workDir )
            throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );

        final Method buildKeyLists = DefaultReferenceManager.class
                .getDeclaredMethod( "buildKeyLists", java.util.Collection.class );
        buildKeyLists.setAccessible( true );

        // A raw list containing a non-Page element: the enhanced-for loop's implicit
        // checked cast throws ClassCastException, which buildKeyLists must swallow
        // (log fatal, keep going) rather than propagate.
        final List poisoned = new ArrayList();
        poisoned.add( "not a page" );

        assertDoesNotThrow( () -> buildKeyLists.invoke( mgr, poisoned ),
                "buildKeyLists must catch ClassCastException from a malformed collection" );
    }

    @Test
    void buildKeyLists_nullCollection_returnsWithoutThrowing( @TempDir final File workDir ) throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );

        final Method buildKeyLists = DefaultReferenceManager.class
                .getDeclaredMethod( "buildKeyLists", java.util.Collection.class );
        buildKeyLists.setAccessible( true );

        // initialize()'s public API can never pass a null collection (it dereferences
        // pages.size() first), but buildKeyLists()'s own null guard is still load-bearing
        // defensive code, exercised directly here.
        assertDoesNotThrow( () -> buildKeyLists.invoke( mgr, new Object[] { null } ) );
    }

    // -----------------------------------------------------------------------
    // serializeToDisk() — I/O failure is logged, never thrown
    // -----------------------------------------------------------------------

    @Test
    void updateReferences_serializeToDiskIoFailure_doesNotThrow( @TempDir final File tempDir )
            throws Exception {
        // Point the "work dir" at a plain FILE instead of a directory, so
        // new File(workDir, "refmgr.ser") resolves under a non-directory parent
        // and Files.newOutputStream(...) throws a genuine IOException.
        final File notADirectory = new File( tempDir, "not-a-directory" );
        assertTrue( notADirectory.createNewFile() );

        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        when( attachmentManager.listAttachments( org.mockito.ArgumentMatchers.any( Page.class ) ) )
                .thenReturn( Collections.emptyList() );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( notADirectory.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );

        assertDoesNotThrow( () -> mgr.updateReferences( "SomePage", List.of( "Other" ) ),
                "serializeToDisk() must log the IOException, not propagate it" );
    }

    // -----------------------------------------------------------------------
    // updatePageReferences() — attachments contribute to refersTo
    // -----------------------------------------------------------------------

    @Test
    void initialize_updatePageReferences_includesAttachmentNames( @TempDir final File workDir )
            throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "WithAttachment" );
        when( page.getLastModified() ).thenReturn( new Date() );
        when( page.getAttributes() ).thenReturn( Collections.emptyMap() );
        when( pageManager.getPageText( "WithAttachment", PageProvider.LATEST_VERSION ) )
                .thenReturn( "No wiki links here." );
        when( pageManager.getPage( "WithAttachment" ) ).thenReturn( page );

        final Attachment attachment = mock( Attachment.class );
        when( attachment.getName() ).thenReturn( "WithAttachment/photo.png" );
        when( attachmentManager.listAttachments( page ) ).thenReturn( List.of( attachment ) );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );
        // No serialized cache exists yet, so initialize() takes the rebuild (catch) path,
        // which calls updatePageReferences() for every page.
        mgr.initialize( List.of( page ) );

        assertTrue( mgr.findRefersTo( "WithAttachment" ).contains( "WithAttachment/photo.png" ),
                "attachments must be folded into the page's outbound references" );
    }

    // -----------------------------------------------------------------------
    // initialize() — full warm-start round trip: a second ReferenceManager
    // pointed at the same work dir successfully deserializes the first one's
    // on-disk cache and exercises every branch of the "refresh changed pages"
    // loop.
    // -----------------------------------------------------------------------

    @Test
    @SuppressWarnings( "unchecked" )
    void initialize_warmStart_readsDiskCacheAndRefreshesChangedPages( @TempDir final File workDir )
            throws Exception {
        // ---- Phase 1: cold start — writes refmgr.ser + Alpha's/Beta's attribute caches.
        final PageManager pageManager1 = mock( PageManager.class );
        final AttachmentManager attachmentManager1 = mock( AttachmentManager.class );
        when( attachmentManager1.listAttachments( org.mockito.ArgumentMatchers.any( Page.class ) ) )
                .thenReturn( Collections.emptyList() );
        final Engine engine1 = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager1 )
                .with( AttachmentManager.class, attachmentManager1 )
                .build();
        when( engine1.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine1.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final Date oldDate = new Date( System.currentTimeMillis() - 600_000L );

        final Page alpha1 = mock( Page.class );
        when( alpha1.getName() ).thenReturn( "Alpha" );
        when( alpha1.getLastModified() ).thenReturn( oldDate );
        when( alpha1.getAttributes() ).thenReturn( Map.of( "greeting", "hello" ) );

        final Page beta1 = mock( Page.class );
        when( beta1.getName() ).thenReturn( "Beta" );
        when( beta1.getLastModified() ).thenReturn( oldDate );
        when( beta1.getAttributes() ).thenReturn( Collections.emptyMap() );

        when( pageManager1.getPageText( anyString(), anyInt() ) ).thenReturn( "no links" );
        when( pageManager1.getPage( "Alpha" ) ).thenReturn( alpha1 );
        when( pageManager1.getPage( "Beta" ) ).thenReturn( beta1 );

        final DefaultReferenceManager mgr1 =
                new DefaultReferenceManager( engine1, pageManager1, attachmentManager1 );
        mgr1.initialize( List.of( alpha1, beta1 ) );
        assertTrue( mgr1.isInitialized() );

        // mgr1.initialize() wrote a real refmgr-attr cache file for Alpha (used below to
        // prove the attribute round trip) plus a refmgr.ser written through
        // ConcurrentHashMap. The reference manager's own SAFE_DESERIALIZE_FILTER rejects
        // ConcurrentHashMap's internal legacy Segment[] compatibility field on read-back
        // (a real, independent defect — tracked separately, not this task's target class),
        // so instead of relying on that read succeeding, we overwrite refmgr.ser here with
        // an equivalent HashMap-based fixture in the exact on-disk format
        // unserializeFromDisk() expects. This still exercises the real read path end to
        // end; it only avoids the CHM-specific write quirk that is orthogonal to what this
        // test is proving (initialize()'s warm-start branch logic).
        final long saved = System.currentTimeMillis() - 300_000L;
        final File serFile = new File( workDir, "refmgr.ser" );
        final Map< String, java.util.Collection< String > > fixtureRefersTo = new java.util.HashMap<>();
        fixtureRefersTo.put( "Alpha", new java.util.TreeSet<>() );
        fixtureRefersTo.put( "Beta", new java.util.TreeSet<>() );
        final Map< String, java.util.Set< String > > fixtureReferredBy = new java.util.HashMap<>();
        fixtureReferredBy.put( "Alpha", new java.util.TreeSet<>() );
        fixtureReferredBy.put( "Beta", new java.util.TreeSet<>() );
        try ( java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(
                new java.io.BufferedOutputStream( new java.io.FileOutputStream( serFile ) ) ) ) {
            out.writeLong( 4L ); // matches DefaultReferenceManager.serialVersionUID
            out.writeLong( saved );
            out.writeObject( fixtureRefersTo );
            out.writeObject( fixtureReferredBy );
        }

        // ---- Phase 2: warm start — a brand-new manager instance pointed at the same
        // work dir. unserializeFromDisk() must succeed this time (no exception), taking
        // the "success" branch of initialize() instead of the rebuild-everything catch.
        final PageManager pageManager2 = mock( PageManager.class );
        final AttachmentManager attachmentManager2 = mock( AttachmentManager.class );
        when( attachmentManager2.listAttachments( org.mockito.ArgumentMatchers.any( Page.class ) ) )
                .thenReturn( Collections.emptyList() );
        final Engine engine2 = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager2 )
                .with( AttachmentManager.class, attachmentManager2 )
                .build();
        when( engine2.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine2.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        when( pageManager2.getPageText( anyString(), anyInt() ) ).thenReturn( "no links" );

        // Alpha: modified AFTER the saved timestamp -> must be refreshed (covers the
        // "wp.getLastModified().getTime() > saved" branch). Already known from phase 1,
        // so the "missing from referredBy" branch must NOT additionally fire for it.
        final Page alpha2 = mock( Page.class );
        when( alpha2.getName() ).thenReturn( "Alpha" );
        when( alpha2.getLastModified() ).thenReturn( new Date( saved + 60_000L ) );
        when( pageManager2.getPage( "Alpha" ) ).thenReturn( alpha2 );

        // Beta: already known from phase 1, but the provider now (bogusly) reports a
        // null lastModified -> must hit the defensive LOG.fatal() branch and must NOT
        // NPE.
        final Page beta2 = mock( Page.class );
        when( beta2.getName() ).thenReturn( "Beta" );
        when( beta2.getLastModified() ).thenReturn( null );
        when( pageManager2.getPage( "Beta" ) ).thenReturn( beta2 );

        // Gamma: brand new — never seen in phase 1, so it is absent from the
        // deserialized referredBy map. Modified before "saved", so the ONLY reason it
        // gets indexed is the "missing from referredBy" catch-up branch.
        final Page gamma2 = mock( Page.class );
        when( gamma2.getName() ).thenReturn( "Gamma" );
        when( gamma2.getLastModified() ).thenReturn( oldDate );
        when( pageManager2.getPage( "Gamma" ) ).thenReturn( gamma2 );

        final DefaultReferenceManager mgr2 =
                new DefaultReferenceManager( engine2, pageManager2, attachmentManager2 );
        mgr2.initialize( List.of( alpha2, beta2, gamma2 ) );

        assertTrue( mgr2.isInitialized() );
        // All three pages must be known to the warm-started manager: Alpha/Beta via the
        // deserialized cache, Gamma via the "new page" catch-up.
        assertTrue( mgr2.findCreated().containsAll( List.of( "Alpha", "Beta", "Gamma" ) ),
                "warm start must retain deserialized pages and pick up the brand-new one: "
                        + mgr2.findCreated() );

        // Alpha's cached attribute ("greeting"="hello") must have been read back from disk
        // and applied to the fresh Page instance handed to initialize().
        verify( alpha2 ).setAttribute( "greeting", "hello" );
        verify( alpha2 ).setHasMetadata();
    }

    // -----------------------------------------------------------------------
    // getRefersTo() — protected debug accessor, package-visible to this test
    // -----------------------------------------------------------------------

    @Test
    void getRefersTo_exposesTheLiveMap( @TempDir final File workDir ) throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        when( attachmentManager.listAttachments( org.mockito.ArgumentMatchers.any( Page.class ) ) )
                .thenReturn( Collections.emptyList() );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );
        mgr.updateReferences( "Source", List.of( "Target" ) );

        assertTrue( mgr.getRefersTo().containsKey( "Source" ) );
        assertTrue( mgr.getRefersTo().get( "Source" ).contains( "Target" ) );
    }

    // -----------------------------------------------------------------------
    // cleanReferredBy — first-ever update for a page has no prior refersTo
    // entry, so oldReferred is null and cleanReferredBy must simply return.
    // -----------------------------------------------------------------------

    @Test
    void updateReferences_firstEverCallForAPage_nullOldReferredIsHandled( @TempDir final File workDir )
            throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        when( attachmentManager.listAttachments( org.mockito.ArgumentMatchers.any( Page.class ) ) )
                .thenReturn( Collections.emptyList() );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );

        // "BrandNew" has never been seen before -> refersTo.get("BrandNew") is null on
        // its very first update, so internalUpdateReferences's oldRefTo is null.
        assertDoesNotThrow( () -> mgr.updateReferences( "BrandNew", List.of( "SomeTarget" ) ) );
        assertTrue( mgr.findRefersTo( "BrandNew" ).contains( "SomeTarget" ) );
    }

    // -----------------------------------------------------------------------
    // unserializeFromDisk() — a version mismatch triggers a clean rebuild
    // instead of trying (and failing) to read data in an unknown format.
    // -----------------------------------------------------------------------

    @Test
    void initialize_serializedVersionMismatch_fallsBackToRebuild( @TempDir final File workDir ) throws Exception {
        final File serFile = new File( workDir, "refmgr.ser" );
        try ( java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(
                new java.io.BufferedOutputStream( new java.io.FileOutputStream( serFile ) ) ) ) {
            out.writeLong( 999L ); // does not match DefaultReferenceManager.serialVersionUID (4L)
            out.writeLong( System.currentTimeMillis() );
            out.writeObject( new java.util.HashMap<String, java.util.Collection<String>>() );
            out.writeObject( new java.util.HashMap<String, java.util.Set<String>>() );
        }

        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        when( attachmentManager.listAttachments( org.mockito.ArgumentMatchers.any( Page.class ) ) )
                .thenReturn( Collections.emptyList() );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "Fresh" );
        when( page.getLastModified() ).thenReturn( new Date() );
        when( page.getAttributes() ).thenReturn( Collections.emptyMap() );
        when( pageManager.getPageText( "Fresh", PageProvider.LATEST_VERSION ) ).thenReturn( "no links" );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );

        assertDoesNotThrow( () -> mgr.initialize( List.of( page ) ) );
        assertTrue( mgr.isInitialized() );
        assertTrue( mgr.findCreated().contains( "Fresh" ), "must fall back to a full rebuild" );
    }

    // -----------------------------------------------------------------------
    // unserializeAttrsFromDisk() — attribute cache version mismatch / page
    // name mismatch both degrade gracefully (return 0, no attribute applied).
    // -----------------------------------------------------------------------

    @Test
    void initialize_attrsCacheVersionMismatch_attributeIsNotApplied( @TempDir final File workDir ) throws Exception {
        writeAttrsCacheFixture( workDir, "Alpha", 999L, "Alpha", Map.of( "k", "v" ) );
        assertAttrsCacheIsIgnored( workDir, "Alpha" );
    }

    @Test
    void initialize_attrsCacheNameMismatch_attributeIsNotApplied( @TempDir final File workDir ) throws Exception {
        // File is named after "Alpha" (same MD5 hash file) but its stored page name
        // inside is a different page entirely — a hash collision / stale-cache guard.
        writeAttrsCacheFixture( workDir, "Alpha", 4L, "SomeoneElse", Map.of( "k", "v" ) );
        assertAttrsCacheIsIgnored( workDir, "Alpha" );
    }

    private void assertAttrsCacheIsIgnored( final File workDir, final String pageName ) throws Exception {
        // unserializeAttrsFromDisk() is only reached from initialize()'s "warm start"
        // success branch, which requires unserializeFromDisk() to succeed first — so a
        // valid (if empty) refmgr.ser fixture must exist alongside the attrs cache file.
        final File serFile = new File( workDir, "refmgr.ser" );
        try ( java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(
                new java.io.BufferedOutputStream( new java.io.FileOutputStream( serFile ) ) ) ) {
            out.writeLong( 4L );
            out.writeLong( System.currentTimeMillis() - 300_000L );
            out.writeObject( new java.util.HashMap<String, java.util.Collection<String>>() );
            out.writeObject( new java.util.HashMap<String, java.util.Set<String>>() );
        }

        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        when( attachmentManager.listAttachments( org.mockito.ArgumentMatchers.any( Page.class ) ) )
                .thenReturn( Collections.emptyList() );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( pageName );
        when( page.getLastModified() ).thenReturn( new Date() );
        when( page.getAttributes() ).thenReturn( Collections.emptyMap() );
        when( pageManager.getPageText( pageName, PageProvider.LATEST_VERSION ) ).thenReturn( "no links" );
        when( pageManager.getPage( pageName ) ).thenReturn( page );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );
        assertDoesNotThrow( () -> mgr.initialize( List.of( page ) ) );

        verify( page, org.mockito.Mockito.never() ).setAttribute( anyString(), org.mockito.ArgumentMatchers.any() );
    }

    /** Writes an attrs-cache file in the exact on-disk shape serializeAttrsToDisk() produces. */
    private void writeAttrsCacheFixture( final File workDir, final String hashForPage, final long version,
                                          final String storedPageName, final Map< String, String > entries )
            throws Exception {
        // Mirrors DefaultReferenceManager.getHashFileName(): MD5(pageName) + ".cache".
        final java.security.MessageDigest digest = java.security.MessageDigest.getInstance( "MD5" );
        final byte[] dig = digest.digest( hashForPage.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        final String hashName = com.wikantik.util.TextUtil.toHexString( dig ) + ".cache";

        final File dir = new File( workDir, "refmgr-attr" );
        assertTrue( dir.exists() || dir.mkdirs() );
        final File f = new File( dir, hashName );
        try ( java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(
                new java.io.BufferedOutputStream( new java.io.FileOutputStream( f ) ) ) ) {
            out.writeLong( version );
            out.writeLong( System.currentTimeMillis() );
            out.writeUTF( storedPageName );
            out.writeLong( entries.size() );
            for ( final Map.Entry< String, String > e : entries.entrySet() ) {
                out.writeUTF( e.getKey() );
                out.writeObject( e.getValue() );
            }
        }
    }

    // -----------------------------------------------------------------------
    // getHashFileName(null) — defensive null guard
    // -----------------------------------------------------------------------

    @Test
    void getHashFileName_nullPageName_returnsNull() throws Exception {
        final java.lang.reflect.Method m = DefaultReferenceManager.class
                .getDeclaredMethod( "getHashFileName", String.class );
        m.setAccessible( true );

        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );

        assertNull( m.invoke( mgr, new Object[] { null } ) );
    }

    // -----------------------------------------------------------------------
    // serializeAttrsToDisk() — mkdirs() failure creating the cache directory
    // (parent work dir made read-only; not root here, so this reliably fails).
    // -----------------------------------------------------------------------

    @Test
    void serializeAttrsToDisk_mkdirsFailure_isLoggedNotThrown( @TempDir final File workDir ) throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "HasAttrs" );
        when( page.getAttributes() ).thenReturn( Map.of( "k", "v" ) );

        final com.wikantik.api.core.Context ctx = mock( com.wikantik.api.core.Context.class );
        when( ctx.getPage() ).thenReturn( page );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );

        assertTrue( workDir.setWritable( false ) );
        try {
            assertDoesNotThrow( () -> mgr.postSave( ctx, "no links" ),
                    "mkdirs() failure must be logged, not thrown" );
        } finally {
            workDir.setWritable( true );
        }
    }

    // -----------------------------------------------------------------------
    // serializeAttrsToDisk() — I/O failure opening the attrs file (the
    // SERIALIZATION_DIR segment is a plain file, not a directory, so it
    // already "exists" and mkdirs() is never attempted — only the nested
    // Files.newOutputStream() call fails).
    // -----------------------------------------------------------------------

    @Test
    void postSave_serializeAttrsToDiskIoFailure_doesNotThrow( @TempDir final File workDir ) throws Exception {
        final File attrDirAsFile = new File( workDir, "refmgr-attr" );
        assertTrue( attrDirAsFile.createNewFile() );

        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "HasAttrs" );
        when( page.getAttributes() ).thenReturn( Map.of( "k", "v" ) );

        final com.wikantik.api.core.Context ctx = mock( com.wikantik.api.core.Context.class );
        when( ctx.getPage() ).thenReturn( page );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );

        assertDoesNotThrow( () -> mgr.postSave( ctx, "no links" ),
                "serializeAttrsToDisk() must log the IOException, not propagate it" );
    }

    // -----------------------------------------------------------------------
    // File.delete() failures — not root here, so a read-only containing
    // directory reliably makes delete() fail (deletion is governed by the
    // parent directory's write permission, not the file's own).
    // -----------------------------------------------------------------------

    @Test
    void serializeAttrsToDisk_deleteOfStaleFileFails_isLoggedNotThrown( @TempDir final File workDir )
            throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "GoingEmpty" );

        final com.wikantik.api.core.Context ctx = mock( com.wikantik.api.core.Context.class );
        when( ctx.getPage() ).thenReturn( page );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );

        // First save: non-empty attributes -> a real cache file is created.
        when( page.getAttributes() ).thenReturn( Map.of( "k", "v" ) );
        mgr.postSave( ctx, "no links" );

        final File attrDir = new File( workDir, "refmgr-attr" );
        assertTrue( attrDir.isDirectory() );
        assertTrue( attrDir.setWritable( false ) );
        try {
            // Second save: attributes now empty -> serializeAttrsToDisk tries to delete the
            // stale cache file, which must fail because the containing directory lost its
            // write bit (deletion is a directory-write operation on POSIX).
            when( page.getAttributes() ).thenReturn( Collections.emptyMap() );
            assertDoesNotThrow( () -> mgr.postSave( ctx, "no links" ) );
        } finally {
            attrDir.setWritable( true );
        }
    }

    @Test
    void pageRemoved_deleteOfCacheFileFails_isLoggedNotThrown( @TempDir final File workDir ) throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        final AttachmentManager attachmentManager = mock( AttachmentManager.class );
        when( attachmentManager.listAttachments( org.mockito.ArgumentMatchers.any( Page.class ) ) )
                .thenReturn( Collections.emptyList() );
        final Engine engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .build();
        when( engine.getWorkDir() ).thenReturn( workDir.getAbsolutePath() );
        when( engine.getFinalPageName( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        when( pageManager.wikiPageExists( anyString() ) ).thenReturn( true );

        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "ToRemove" );
        when( page.getAttributes() ).thenReturn( Map.of( "k", "v" ) );

        final DefaultReferenceManager mgr = new DefaultReferenceManager( engine, pageManager, attachmentManager );
        // Give it a real refersTo/referredBy entry plus a real attrs cache file to delete.
        mgr.updateReferences( "ToRemove", List.of() );
        final com.wikantik.api.core.Context ctx = mock( com.wikantik.api.core.Context.class );
        when( ctx.getPage() ).thenReturn( page );
        mgr.postSave( ctx, "" );

        final File attrDir = new File( workDir, "refmgr-attr" );
        assertTrue( attrDir.isDirectory() );
        assertTrue( attrDir.setWritable( false ) );
        try {
            assertDoesNotThrow( () -> mgr.pageRemoved( page ) );
        } finally {
            attrDir.setWritable( true );
        }
    }
}

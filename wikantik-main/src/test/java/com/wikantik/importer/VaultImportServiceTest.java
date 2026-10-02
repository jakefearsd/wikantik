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
package com.wikantik.importer;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VaultImportServiceTest {

    private TestEngine engine;
    private PageManager pm;
    private AttachmentManager am;
    private ImportPageSink sink;
    private VaultImportService service;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        pm = engine.getManager( PageManager.class );
        am = engine.getManager( AttachmentManager.class );
        sink = new EngineImportPageSink( engine, pm, am, new PageSaveHelper( engine, pm ) );
        service = serviceWith( sink );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private VaultImportService serviceWith( final ImportPageSink s ) {
        final ImportLimits limits = ImportLimits.defaults();
        final VaultImportPlanner planner = new VaultImportPlanner( FrontmatterSchema.defaultSchema(),
            AttachmentGate.fromProperties( new Properties() ), limits.maxPages() );
        return new VaultImportService( limits, planner, this::snapshot, s );
    }

    /** The TestEngine registry does not ship Main; the fixture's Main.md must still be reserved. */
    private WikiSnapshot snapshot() throws com.wikantik.api.exceptions.ProviderException {
        final WikiSnapshot real = EngineWikiSnapshot.capture( pm,
            CoreSubsystemBridge.fromLegacyEngine( engine ).systemPageRegistry(), null );
        return new WikiSnapshot() {
            @Override public java.util.Optional< String > existingPage( final String n ) {
                return real.existingPage( n );
            }
            @Override public boolean isSystemPage( final String n ) {
                return "Main".equals( n ) || real.isSystemPage( n );
            }
            @Override public java.util.Optional< String > hubPage( final String c ) {
                return real.hubPage( c );
            }
        };
    }

    private static ItemResult result( final JobView v, final String name ) {
        return v.results().stream().filter( r -> r.name().equals( name ) ).findFirst().orElseThrow();
    }

    @Test
    void applyCreatesPagesHubsAndAttachments() throws Exception {
        final SpooledUpload up = TestVaults.upload( TestVaults.zip( TestVaults.fixture() ), "fixture.zip" );
        final PlanResult plan = service.plan( up, ImportOptions.parse( "folders", null ) );
        for ( final PlannedPage p : plan.plan().pages() ) {
            if ( !"Main".equals( p.name() ) ) {
                assertFalse( pm.wikiPageExists( p.name() ), p.name() );
            }
        }
        final VaultImportJob job = service.newJob( "j1", "admin", "admin", up, plan, () -> true );
        job.run();
        final JobView v = job.view();
        assertEquals( JobState.DONE, v.state(), v.message() );
        assertEquals( v.total(), v.done() );
        assertTrue( pm.wikiPageExists( "Projects" ) && pm.wikiPageExists( "Notes Hub" ) && pm.wikiPageExists( "Beta v2" ) );
        final String welcome = pm.getPureText( "Welcome", WikiProvider.LATEST_VERSION );
        assertTrue( welcome.contains( "[[Alpha|Projects/Alpha]]" ), welcome );
        assertFalse( welcome.contains( "cluster:" ) );
        assertNotNull( am.getAttachmentInfo( "Welcome/diagram.png" ) );
        assertEquals( "Imported from Obsidian vault fixture.zip", pm.getPage( "Alpha" ).getAttribute( Page.CHANGENOTE ) );
        assertEquals( List.of( "Notes Hub", "Projects", "Deep Hub" ), v.summary().get( "hubs" ) );
        assertEquals( ItemStatus.SKIPPED_RESERVED, result( v, "Main" ).status() );
        assertFalse( Files.exists( up.file() ) );
    }

    @Test
    void pageCreatedDuringJobIsSkippedNotOverwritten() throws Exception {
        final SpooledUpload up = TestVaults.upload( TestVaults.zipText( Map.of( "A.md", "vault A", "B.md", "vault B" ) ), "v.zip" );
        final PlanResult plan = service.plan( up, ImportOptions.parse( "none", null ) );
        engine.saveText( "A", "someone else's A" );
        final VaultImportJob job = service.newJob( "j2", "admin", "admin", up, plan, () -> true );
        job.run();
        assertEquals( ItemStatus.SKIPPED_EXISTS, result( job.view(), "A" ).status() );
        assertEquals( "someone else's A", pm.getPureText( "A", WikiProvider.LATEST_VERSION ).trim() );
        assertEquals( ItemStatus.CREATED, result( job.view(), "B" ).status() );
    }

    @Test
    void blockedAttachmentReportedAndReferenceKept() throws Exception {
        final SpooledUpload up = TestVaults.upload( TestVaults.zip( TestVaults.fixture() ), "fixture.zip" );
        final PlanResult plan = service.plan( up, ImportOptions.parse( "folders", null ) );
        assertTrue( plan.plan().attachments().stream().anyMatch( a -> a.status() == AttachmentStatus.BLOCKED ) );
        assertTrue( plan.attachmentsToImport().stream().noneMatch( a -> a.fileName().contains( "evil" ) ) );
        service.newJob( "j3", "admin", "admin", up, plan, () -> true ).run();
        assertTrue( pm.getPureText( "Alpha", WikiProvider.LATEST_VERSION ).contains( "![[evil.svg]]" ) );
    }

    @Test
    void frontmatterErrorPageFailsAlone() throws Exception {
        final ImportPageSink failing = new ImportPageSink() {
            @Override public boolean pageExists( final String name ) {
                return sink.pageExists( name );
            }
            @Override public List< String > savePage( final String name, final String body,
                    final Map< String, Object > metadata, final String author, final String changeNote )
                    throws ImportSaveException {
                if ( "B".equals( name ) ) {
                    throw new ImportSaveException( "frontmatter validation failed: audience", null );
                }
                return sink.savePage( name, body, metadata, author, changeNote );
            }
            @Override public void storeAttachment( final String page, final String fileName, final InputStream in,
                    final String author ) throws Exception {
                sink.storeAttachment( page, fileName, in, author );
            }
        };
        final VaultImportService svc = serviceWith( failing );
        final SpooledUpload up = TestVaults.upload( TestVaults.zipText(
            Map.of( "A.md", "a", "B.md", "b", "C.md", "c" ) ), "v.zip" );
        final PlanResult plan = svc.plan( up, ImportOptions.parse( "none", null ) );
        final VaultImportJob job = svc.newJob( "j4", "admin", "admin", up, plan, () -> true );
        job.run();
        final JobView v = job.view();
        assertEquals( JobState.DONE, v.state() );
        assertEquals( ItemStatus.CREATED, result( v, "A" ).status() );
        assertEquals( ItemStatus.CREATED, result( v, "C" ).status() );
        assertEquals( ItemStatus.FAILED, result( v, "B" ).status() );
        assertTrue( result( v, "B" ).reason().contains( "audience" ) );
    }

    @Test
    void unreferencedFilesAreNeverRead() throws Exception {
        final byte[] big = new byte[ 2 * 1024 * 1024 ];
        new java.util.Random( 7 ).nextBytes( big );
        final Map< String, byte[] > vault = new java.util.LinkedHashMap<>();
        vault.put( "N.md", "no refs".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        vault.put( "big.png", big );
        final AtomicInteger stored = new AtomicInteger();
        final ImportPageSink counting = new ImportPageSink() {
            @Override public boolean pageExists( final String name ) {
                return sink.pageExists( name );
            }
            @Override public List< String > savePage( final String name, final String body,
                    final Map< String, Object > metadata, final String author, final String changeNote )
                    throws ImportSaveException {
                return sink.savePage( name, body, metadata, author, changeNote );
            }
            @Override public void storeAttachment( final String page, final String fileName, final InputStream in,
                    final String author ) {
                stored.incrementAndGet();
            }
        };
        final VaultImportService svc = serviceWith( counting );
        final SpooledUpload up = TestVaults.upload( TestVaults.zip( vault ), "v.zip" );
        final PlanResult plan = svc.plan( up, ImportOptions.parse( "none", null ) );
        assertEquals( 1, plan.plan().totals().attachmentsSkipped() );
        final VaultImportJob job = svc.newJob( "j5", "admin", "admin", up, plan, () -> true );
        job.run();
        assertEquals( 0, stored.get() );
        assertEquals( 1, job.view().total() );
        assertEquals( 1, job.view().done() );
        assertEquals( 1, job.view().summary().get( "unreferencedFiles" ) );
    }

    @Test
    void revokedPermissionFailsJobBeforeAnyWrite() throws Exception {
        final SpooledUpload up = TestVaults.upload( TestVaults.zipText( Map.of( "A.md", "a" ) ), "v.zip" );
        final PlanResult plan = service.plan( up, ImportOptions.parse( "none", null ) );
        final VaultImportJob job = service.newJob( "j6", "admin", "admin", up, plan, () -> false );
        job.run();
        assertEquals( JobState.FAILED, job.view().state() );
        assertFalse( pm.wikiPageExists( "A" ) );
        assertFalse( Files.exists( up.file() ) );
    }

    private ImportPageSink decorate( final java.util.function.Function< String, Throwable > savePageThrows,
                                     final String rejection, final AtomicInteger stored ) {
        return new ImportPageSink() {
            @Override public boolean pageExists( final String name ) {
                return sink.pageExists( name );
            }
            @Override public List< String > savePage( final String name, final String body,
                    final Map< String, Object > metadata, final String author, final String changeNote )
                    throws ImportSaveException {
                final Throwable t = savePageThrows.apply( name );
                if ( t instanceof Error err ) {
                    throw err;
                }
                if ( t instanceof RuntimeException re ) {
                    throw re;
                }
                return sink.savePage( name, body, metadata, author, changeNote );
            }
            @Override public java.util.Optional< String > attachmentRejection( final String f, final long size ) {
                return java.util.Optional.ofNullable( rejection );
            }
            @Override public void storeAttachment( final String page, final String fileName, final InputStream in,
                    final String author ) throws Exception {
                stored.incrementAndGet();
                sink.storeAttachment( page, fileName, in, author );
            }
        };
    }

    @Test
    void errorFromSinkFailsJobAndFreesRegistrySlot() throws Exception {
        final VaultImportService svc = serviceWith( decorate( n -> new AssertionError(), null, new AtomicInteger() ) );
        final SpooledUpload up = TestVaults.upload( TestVaults.zipText( Map.of( "A.md", "a" ) ), "v.zip" );
        final PlanResult plan = svc.plan( up, ImportOptions.parse( "none", null ) );
        final List< Runnable > queued = new java.util.ArrayList<>();
        final ImportJobRegistry registry = new ImportJobRegistry( 1, java.time.Clock.systemUTC(), queued::add );
        final VaultImportJob job = registry.start( "alice", id -> svc.newJob( id, "alice", "alice", up, plan, () -> true ) );
        try {
            queued.get( 0 ).run();
        } catch ( final AssertionError e ) {
            fail( "non-VM errors must not escape the job: " + e );
        }
        assertEquals( JobState.FAILED, job.view().state() );
        assertEquals( "java.lang.AssertionError", job.view().message() );
        assertFalse( job.isRunning() );
        assertFalse( Files.exists( up.file() ) );
        registry.start( "alice", id -> ImportTestJobs.job( id, "alice" ) );   // slot and user are free again
    }

    @Test
    void attachmentPolicyIsRecheckedAtApply() throws Exception {
        final AtomicInteger stored = new AtomicInteger();
        final VaultImportService svc = serviceWith( decorate( n -> null, "type no longer allowed", stored ) );
        final SpooledUpload up = TestVaults.upload( TestVaults.zip( TestVaults.fixture() ), "fixture.zip" );
        final PlanResult plan = svc.plan( up, ImportOptions.parse( "folders", null ) );
        assertFalse( plan.attachmentsToImport().isEmpty() );
        final VaultImportJob job = svc.newJob( "j7", "admin", "admin", up, plan, () -> true );
        job.run();
        assertEquals( 0, stored.get() );
        final ItemResult r = result( job.view(), "Welcome/diagram.png" );
        assertEquals( ItemStatus.FAILED, r.status() );
        assertTrue( r.reason().contains( "type no longer allowed" ), r.reason() );
        assertEquals( JobState.DONE, job.view().state() );
    }

    @Test
    void engineSinkAppliesItsGate() {
        final ImportPageSink gated = new EngineImportPageSink( engine, pm, am, new PageSaveHelper( engine, pm ),
            AttachmentGate.fromProperties( new Properties() ) );
        assertTrue( gated.attachmentRejection( "evil.svg", 10 ).isPresent() );
        assertTrue( gated.attachmentRejection( "ok.png", 10 ).isEmpty() );
        assertTrue( sink.attachmentRejection( "evil.svg", 10 ).isEmpty() );   // no gate configured
    }

    @Test
    void interruptedThreadFailsJobAndDiscardsUpload() throws Exception {
        final SpooledUpload up = TestVaults.upload( TestVaults.zipText( Map.of( "A.md", "a" ) ), "v.zip" );
        final PlanResult plan = service.plan( up, ImportOptions.parse( "none", null ) );
        final VaultImportJob job = service.newJob( "j8", "admin", "admin", up, plan, () -> true );
        Thread.currentThread().interrupt();
        try {
            job.run();
        } finally {
            Thread.interrupted();   // clear the flag for later tests
        }
        assertEquals( JobState.FAILED, job.view().state() );
        assertEquals( "interrupted", job.view().message() );
        assertFalse( pm.wikiPageExists( "A" ) );
        assertFalse( Files.exists( up.file() ) );
    }

    /** A sink that reads every attachment stream to the end, counting the bytes it was handed. */
    private ImportPageSink byteCounting( final java.util.concurrent.atomic.AtomicLong bytes ) {
        return new ImportPageSink() {
            @Override public boolean pageExists( final String name ) {
                return sink.pageExists( name );
            }
            @Override public List< String > savePage( final String name, final String body,
                    final Map< String, Object > metadata, final String author, final String changeNote )
                    throws ImportSaveException {
                return sink.savePage( name, body, metadata, author, changeNote );
            }
            @Override public void storeAttachment( final String page, final String fileName, final InputStream in,
                    final String author ) throws Exception {
                final byte[] buf = new byte[ 65536 ];
                for ( int n = in.read( buf ); n >= 0; n = in.read( buf ) ) {
                    bytes.addAndGet( n );
                }
            }
        };
    }

    private static final byte[] PNG = { ( byte ) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

    @Test
    void applyReadsTheEntryThePlanValidatedNotTheCentralDirectoryOne() throws Exception {
        final byte[] zip = TestVaults.zipWithHiddenCentralEntry( "A.md",
            "see ![[img.png]]".getBytes( java.nio.charset.StandardCharsets.UTF_8 ), "img.png", PNG,
            new byte[ 64 * 1024 * 1024 ] );
        final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        final VaultImportService svc = serviceWith( byteCounting( bytes ) );
        final SpooledUpload up = TestVaults.upload( zip, "crafted.zip" );
        final PlanResult plan = svc.plan( up, ImportOptions.parse( "none", null ) );
        assertEquals( 1, plan.attachmentsToImport().size() );
        assertEquals( PNG.length, plan.attachmentsToImport().get( 0 ).size() );
        final VaultImportJob job = svc.newJob( "j9", "admin", "admin", up, plan, () -> true );
        job.run();
        assertEquals( JobState.DONE, job.view().state(), job.view().message() );
        assertEquals( PNG.length, bytes.get(), "only the planned (locally validated) bytes may reach the store" );
        assertEquals( ItemStatus.CREATED, result( job.view(), "A/img.png" ).status() );
    }

    @Test
    void attachmentLargerThanPlannedFailsWithoutStoringMore() throws Exception {
        final Map< String, byte[] > vault = new java.util.LinkedHashMap<>();
        vault.put( "A.md", "see ![[img.png]]".getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        vault.put( "img.png", new byte[ 1024 * 1024 ] );
        final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        final VaultImportService svc = serviceWith( byteCounting( bytes ) );
        final SpooledUpload up = TestVaults.upload( TestVaults.zip( vault ), "v.zip" );
        final PlanResult real = svc.plan( up, ImportOptions.parse( "none", null ) );
        final PlannedAttachment a = real.attachmentsToImport().get( 0 );
        final PlannedAttachment shrunk = new PlannedAttachment( a.vaultPath(), a.entryName(), a.owner(), a.fileName(),
            1000, a.status(), a.reason() );
        final PlanResult plan = new PlanResult( real.plan(), real.drafts(), List.of( shrunk ) );
        final VaultImportJob job = svc.newJob( "j10", "admin", "admin", up, plan, () -> true );
        job.run();
        final ItemResult r = result( job.view(), "A/img.png" );
        assertEquals( ItemStatus.FAILED, r.status() );
        assertTrue( r.reason().contains( "size differs from plan" ), r.reason() );
        assertTrue( bytes.get() <= 1000, "read past the planned size: " + bytes.get() );
        assertEquals( JobState.DONE, job.view().state() );
    }

    @Test
    void importerNeverUsesZipFile() throws Exception {
        try ( java.util.stream.Stream< java.nio.file.Path > files = Files.walk(
                java.nio.file.Path.of( "src/main/java/com/wikantik/importer" ) ) ) {
            final List< String > offenders = files.filter( f -> f.toString().endsWith( ".java" ) ).filter( f -> {
                try {
                    return Files.readString( f ).contains( "java.util.zip.ZipFile" );
                } catch ( final java.io.IOException e ) {
                    throw new java.io.UncheckedIOException( e );
                }
            } ).map( Object::toString ).toList();
            assertTrue( offenders.isEmpty(), "ZipFile trusts the central directory; use ZipInputStream: " + offenders );
        }
    }

    @Test
    void bodyThatLooksLikeFrontmatterIsNotReparsedOnSave() throws Exception {
        final SpooledUpload up = TestVaults.upload( TestVaults.zipText(
            Map.of( "Sneaky.md", "---\n---\n---\ntype: hub\ncluster: x\n---\nbody" ) ), "v.zip" );
        final PlanResult plan = service.plan( up, ImportOptions.parse( "none", null ) );
        final VaultImportJob job = service.newJob( "j11", "admin", "admin", up, plan, () -> true );
        job.run();
        assertEquals( ItemStatus.CREATED, result( job.view(), "Sneaky" ).status(), String.valueOf( job.view().results() ) );
        final String saved = pm.getPureText( "Sneaky", WikiProvider.LATEST_VERSION );
        final Map< String, Object > meta = com.wikantik.api.frontmatter.FrontmatterParser.parse( saved ).metadata();
        assertFalse( meta.containsKey( "type" ) || meta.containsKey( "cluster" ), "body re-read as frontmatter: " + meta );
        assertTrue( saved.contains( "type: hub" ), "the body text itself is kept: " + saved );
    }
}

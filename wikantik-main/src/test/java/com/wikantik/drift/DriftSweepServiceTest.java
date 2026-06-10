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
package com.wikantik.drift;

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.managers.PageManager;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.ValidationCtx;
import com.wikantik.ontology.OntologyShaclValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DriftSweepServiceTest {

    private static Page page( final String name ) {
        final Page p = Mockito.mock( Page.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    private static PageManager pm( final Object... namesAndTexts ) throws Exception {
        final PageManager pm = Mockito.mock( PageManager.class );
        final java.util.List< Page > pages = new java.util.ArrayList<>();
        for ( int i = 0; i < namesAndTexts.length; i += 2 ) {
            final Page p = page( ( String ) namesAndTexts[ i ] );
            when( pm.getPureText( p ) ).thenReturn( ( String ) namesAndTexts[ i + 1 ] );
            pages.add( p );
        }
        Mockito.doReturn( pages ).when( pm ).getAllPages();
        return pm;
    }

    private static DriftSweepService service( final PageManager pm,
            final DriftSnapshotRepository repo,
            final java.util.function.Supplier< List< OntologyShaclValidator.Violation > > shacl ) {
        return new DriftSweepService( pm,
                new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() ),
                ValidationCtx.lenient(), shacl, repo );
    }

    @Test
    void sweepAggregatesFrontmatterViolationsByCode() throws Exception {
        // Two pages with a non-canonical status (warning each), one clean, one without frontmatter.
        final PageManager pm = pm(
                "Drifty1", "---\ntype: article\nstatus: bogus-state\n---\n\nbody",
                "Drifty2", "---\ntype: article\nstatus: bogus-state\n---\n\nbody",
                "Clean",   "---\ntype: article\nstatus: active\n---\n\nbody",
                "NoFm",    "Just a body, no frontmatter." );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService svc = service( pm, repo, null );
        final DriftSweepService.SweepOutcome outcome = svc.runSweep( "manual" );

        assertEquals( 4, outcome.pagesScanned() );
        assertFalse( outcome.shaclChecked() );

        @SuppressWarnings( "unchecked" )
        final ArgumentCaptor< List< DriftCount > > counts = ArgumentCaptor.forClass( List.class );
        verify( repo ).insertSweep( any(), eq( 4 ), anyLong(), eq( "manual" ), eq( false ),
                counts.capture() );
        final DriftCount statusDrift = counts.getValue().stream()
                .filter( c -> "status.noncanonical".equals( c.code() ) )
                .findFirst().orElseThrow();
        assertEquals( "frontmatter", statusDrift.family() );
        assertEquals( "WARNING", statusDrift.severity() );
        assertEquals( 2, statusDrift.count() );
    }

    @Test
    void malformedYamlIsCountedNotFatal() throws Exception {
        final PageManager pm = pm(
                "Broken", "---\ntitle: foo: bar: baz\n  bad indent\n---\n\nbody",
                "Clean",  "---\ntype: article\nstatus: active\n---\n\nbody" );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService.SweepOutcome outcome = service( pm, repo, null ).runSweep( "manual" );

        assertEquals( 2, outcome.pagesScanned() );
        final DriftCount yaml = outcome.counts().stream()
                .filter( c -> "yaml.parse".equals( c.code() ) ).findFirst().orElseThrow();
        assertEquals( "ERROR", yaml.severity() );
        assertEquals( 1, yaml.count() );
    }

    @Test
    void unreadablePageIsSkippedAndNotScanned() throws Exception {
        final PageManager pm = Mockito.mock( PageManager.class );
        final Page good = page( "Good" );
        final Page bad = page( "Bad" );
        when( pm.getPureText( good ) ).thenReturn( "---\ntype: article\nstatus: active\n---\n\nbody" );
        when( pm.getPureText( bad ) ).thenThrow( new RuntimeException( "provider exploded" ) );
        Mockito.doReturn( List.of( good, bad ) ).when( pm ).getAllPages();
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService.SweepOutcome outcome = service( pm, repo, null ).runSweep( "manual" );
        assertEquals( 1, outcome.pagesScanned() );
    }

    @Test
    void shaclViolationsCountedByPath() throws Exception {
        final PageManager pm = pm( "Clean", "---\ntype: article\nstatus: active\n---\n\nbody" );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );
        final List< OntologyShaclValidator.Violation > violations = List.of(
                new OntologyShaclValidator.Violation( "wk:e1", "wk:implements", "subject must be technology" ),
                new OntologyShaclValidator.Violation( "wk:e2", "wk:implements", "subject must be technology" ),
                new OntologyShaclValidator.Violation( "wk:e3", "wk:located_in", "object must be place" ) );

        final DriftSweepService.SweepOutcome outcome =
                service( pm, repo, () -> violations ).runSweep( "scheduled" );

        assertTrue( outcome.shaclChecked() );
        assertEquals( 2, outcome.counts().stream()
                .filter( c -> "shacl".equals( c.family() ) && "wk:implements".equals( c.code() ) )
                .findFirst().orElseThrow().count() );
        assertEquals( 1, outcome.counts().stream()
                .filter( c -> "shacl".equals( c.family() ) && "wk:located_in".equals( c.code() ) )
                .findFirst().orElseThrow().count() );
    }

    @Test
    void shaclSourceFailureDegradesToUnchecked() throws Exception {
        final PageManager pm = pm( "Clean", "---\ntype: article\nstatus: active\n---\n\nbody" );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService.SweepOutcome outcome = service( pm, repo,
                () -> { throw new IllegalStateException( "tdb2 gone" ); } ).runSweep( "scheduled" );

        assertFalse( outcome.shaclChecked() );
        verify( repo ).insertSweep( any(), anyInt(), anyLong(), anyString(), eq( false ), anyList() );
    }

    @Test
    void repositoryFailureReleasesTheRunningFlag() throws Exception {
        final PageManager pm = pm( "Clean", "---\ntype: article\nstatus: active\n---\n\nbody" );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenThrow( new IllegalStateException( "db gone" ) );

        final DriftSweepService svc = service( pm, repo, null );
        assertThrows( IllegalStateException.class, () -> svc.runSweep( "manual" ) );
        assertFalse( svc.isRunning() );
    }

    @Test
    @Timeout( value = 10, unit = TimeUnit.SECONDS )
    void concurrentSweepIsRefused() throws Exception {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final PageManager pm = Mockito.mock( PageManager.class );
        final Page slow = page( "Slow" );
        when( pm.getPureText( slow ) ).thenAnswer( inv -> {
            entered.countDown();
            release.await();
            return "---\ntype: article\n---\n\nbody";
        } );
        Mockito.doReturn( List.of( slow ) ).when( pm ).getAllPages();
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService svc = service( pm, repo, null );
        final AtomicReference< Throwable > worker = new AtomicReference<>();
        final Thread t = new Thread( () -> {
            try { svc.runSweep( "manual" ); } catch ( final Throwable e ) { worker.set( e ); }
        } );
        t.start();
        entered.await();
        assertTrue( svc.isRunning() );
        assertThrows( DriftSweepService.SweepAlreadyRunningException.class,
                () -> svc.runSweep( "manual" ) );
        release.countDown();
        t.join();
        assertNull( worker.get() );
        assertFalse( svc.isRunning() );
    }

    @Test
    void currentPageListReturnsOffendersForCode() throws Exception {
        final PageManager pm = pm(
                "Drifty", "---\ntype: article\nstatus: bogus-state\n---\n\nbody",
                "Clean",  "---\ntype: article\nstatus: active\n---\n\nbody",
                "NoFm",   "Just a body." );
        final DriftSweepService svc = service( pm, mock( DriftSnapshotRepository.class ), null );

        final List< PageViolation > offenders = svc.currentPageList( "frontmatter", "status.noncanonical" );
        assertEquals( 1, offenders.size() );
        assertEquals( "Drifty", offenders.get( 0 ).pageName() );
        assertEquals( "status", offenders.get( 0 ).field() );
        assertNotNull( offenders.get( 0 ).message() );
    }

    @Test
    void currentPageListForShaclFiltersByPath() throws Exception {
        final PageManager pm = pm();
        final DriftSweepService svc = service( pm, mock( DriftSnapshotRepository.class ),
                () -> List.of(
                        new OntologyShaclValidator.Violation( "wk:e1", "wk:implements", "bad subject" ),
                        new OntologyShaclValidator.Violation( "wk:e2", "wk:located_in", "bad object" ) ) );

        final List< PageViolation > offenders = svc.currentPageList( "shacl", "wk:implements" );
        assertEquals( 1, offenders.size() );
        assertEquals( "wk:e1", offenders.get( 0 ).pageName() );
    }

    @Test
    void progressIsIdleBeforeAnySweep() {
        final DriftSweepService svc = service( mock( PageManager.class ),
                mock( DriftSnapshotRepository.class ), null );
        final DriftSweepService.SweepProgress p = svc.progress();
        assertFalse( p.running() );
        assertNull( p.phase() );
        assertEquals( 0, p.pagesScanned() );
        assertEquals( 0, p.totalPages() );
    }

    @Test
    void progressReportsTotalAndPhaseMidSweep() throws Exception {
        final CountDownLatch entered = new CountDownLatch( 1 );
        final CountDownLatch release = new CountDownLatch( 1 );
        final PageManager pm = Mockito.mock( PageManager.class );
        final Page p1 = page( "P1" );
        final Page p2 = page( "P2" );
        when( pm.getPureText( p1 ) ).thenAnswer( inv -> {
            entered.countDown();
            release.await();
            return "---\ntype: article\nstatus: active\n---\n\nbody";
        } );
        when( pm.getPureText( p2 ) ).thenReturn( "---\ntype: article\nstatus: active\n---\n\nbody" );
        Mockito.doReturn( List.of( p1, p2 ) ).when( pm ).getAllPages();
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenReturn( 1L );

        final DriftSweepService svc = service( pm, repo, null );
        final Thread t = new Thread( () -> svc.runSweep( "manual" ) );
        t.start();
        assertTrue( entered.await( 5, TimeUnit.SECONDS ) );

        final DriftSweepService.SweepProgress mid = svc.progress();
        assertTrue( mid.running() );
        assertEquals( "frontmatter", mid.phase() );
        assertEquals( 2, mid.totalPages() );

        release.countDown();
        t.join();

        final DriftSweepService.SweepProgress after = svc.progress();
        assertFalse( after.running() );
        assertNull( after.phase() );
        assertEquals( 0, after.totalPages() );
        assertEquals( 0, after.pagesScanned() );
    }

    @Test
    void progressResetsAfterRepositoryFailure() throws Exception {
        final PageManager pm = pm( "Clean", "---\ntype: article\nstatus: active\n---\n\nbody" );
        final DriftSnapshotRepository repo = mock( DriftSnapshotRepository.class );
        when( repo.insertSweep( any(), anyInt(), anyLong(), anyString(), anyBoolean(), anyList() ) )
                .thenThrow( new IllegalStateException( "db gone" ) );

        final DriftSweepService svc = service( pm, repo, null );
        assertThrows( IllegalStateException.class, () -> svc.runSweep( "manual" ) );

        final DriftSweepService.SweepProgress p = svc.progress();
        assertFalse( p.running() );
        assertNull( p.phase() );
        assertEquals( 0, p.totalPages() );
    }
}

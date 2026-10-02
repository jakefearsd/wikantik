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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Applies a reviewed {@link PlanResult}: hubs and notes first (the plan orders drafts hub-first), then
 * attachments streamed from the spooled zip. Never overwrites; per-item errors never fail the job.
 *
 * <p>Attachments are read in one sequential {@link ZipInputStream} pass — the same local headers the plan
 * validated — never through the central directory, and each entry is bounded by its planned size and the
 * zip-bomb ratio guard.</p>
 */
public final class VaultImportJob implements Runnable {

    private static final Logger LOG = LogManager.getLogger( VaultImportJob.class );

    private final String id;
    private final String owner;
    private final String author;
    private final SpooledUpload upload;
    /** Released (nulled) when the job finishes, so a retained job does not pin every draft body. */
    private PlanResult plan;
    private final List< String > hubDrafts;
    private final int unreferencedFiles;
    private final ImportPageSink sink;
    private final BooleanSupplier permitted;
    private final Instant createdAt = Instant.now();
    private final int total;

    private final List< ItemResult > results = new ArrayList<>();
    private JobState state = JobState.RUNNING;
    private int done;
    private String current;
    private String message;
    private Instant finishedAt;
    private Clock clock = Clock.systemUTC();

    public VaultImportJob( final String id, final String owner, final String author, final SpooledUpload upload,
                           final PlanResult plan, final ImportPageSink sink, final BooleanSupplier permitted ) {
        this.id = id;
        this.owner = owner;
        this.author = author;
        this.upload = upload;
        this.plan = plan;
        this.sink = sink;
        this.permitted = permitted;
        this.total = plan.drafts().size() + plan.attachmentsToImport().size();
        this.hubDrafts = plan.drafts().stream().filter( PageDraft::hub ).map( PageDraft::name ).toList();
        this.unreferencedFiles = plan.plan().totals().attachmentsSkipped();
    }

    public String id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** When the job ended, or null while it is running. */
    public synchronized Instant finishedAt() {
        return finishedAt;
    }

    public synchronized boolean isRunning() {
        return state == JobState.RUNNING;
    }

    @Override
    public void run() {
        try {
            if ( !permitted.getAsBoolean() ) {
                finish( JobState.FAILED, "createPages permission is no longer granted" );
                return;
            }
            recordPlanOutcomes();
            final Set< String > created = new HashSet<>();
            for ( final PageDraft d : plan.drafts() ) {
                createPage( d, created );
            }
            importAttachments( created );
            finish( JobState.DONE, null );
        } catch ( final Throwable e ) { // NOPMD - a job must always reach a terminal state, even on an Error
            LOG.warn( "Obsidian import job {} failed: {}", id, e.toString(), e );
            finish( JobState.FAILED, describe( e ) );
            if ( e instanceof VirtualMachineError ) {
                throw (VirtualMachineError) e;
            }
        } finally {
            discardUpload();
        }
    }

    private static String describe( final Throwable e ) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getName();
    }

    private static void checkNotInterrupted() {
        if ( Thread.currentThread().isInterrupted() ) {
            throw new IllegalStateException( "interrupted" );
        }
    }

    /** Lets the registry stamp {@code finishedAt} from its own clock. */
    synchronized void useClock( final Clock c ) {
        this.clock = c;
    }

    /** True until the job finishes; afterwards the plan (with every draft body) is released. */
    synchronized boolean retainsPlan() {
        return plan != null;
    }

    /** Deletes the spooled zip. Idempotent. */
    public void discardUpload() {
        upload.delete();
    }

    public synchronized JobView view() {
        return new JobView( id, state, done, total, current, List.copyOf( results ), summary(), message );
    }

    // ---- apply -----------------------------------------------------------------------------

    private void recordPlanOutcomes() {
        for ( final PlannedPage p : plan.plan().pages() ) {
            switch ( p.status() ) {
                case SKIPPED_EXISTS -> record( "page", p.name(), p.vaultPath(), ItemStatus.SKIPPED_EXISTS, p.reason(), List.of() );
                case SKIPPED_RESERVED -> record( "page", p.name(), p.vaultPath(), ItemStatus.SKIPPED_RESERVED, p.reason(), List.of() );
                case WILL_FAIL -> record( "page", p.name(), p.vaultPath(), ItemStatus.FAILED, p.reason(), List.of() );
                case NEW -> { /* handled by createPage */ }
            }
        }
    }

    private void createPage( final PageDraft d, final Set< String > created ) {
        checkNotInterrupted();
        setCurrent( d.name() );
        if ( sink.pageExists( d.name() ) ) {
            record( "page", d.name(), d.vaultPath(), ItemStatus.SKIPPED_EXISTS, "created by someone else after planning", List.of() );
            tick();
            return;
        }
        try {
            final List< String > warnings = sink.savePage( d.name(), d.body(), d.metadata(), author,
                "Imported from Obsidian vault " + upload.originalName() );
            created.add( d.name() );
            record( "page", d.name(), d.vaultPath(), ItemStatus.CREATED, null, warnings );
        } catch ( final ImportSaveException e ) {
            LOG.warn( "Obsidian import {}: page {} failed: {}", id, d.name(), e.getMessage(), e );
            record( "page", d.name(), d.vaultPath(), ItemStatus.FAILED, e.getMessage(), List.of() );
        }
        tick();
    }

    private void importAttachments( final Set< String > created ) throws IOException {
        if ( plan.attachmentsToImport().isEmpty() ) {
            return;
        }
        final Map< String, Deque< PlannedAttachment > > pending = new LinkedHashMap<>();
        for ( final PlannedAttachment a : plan.attachmentsToImport() ) {
            pending.computeIfAbsent( a.entryName(), k -> new ArrayDeque<>() ).add( a );
        }
        try ( CountingInputStream raw = new CountingInputStream( Files.newInputStream( upload.file() ) );
              ZipInputStream zin = new ZipInputStream( raw, StandardCharsets.UTF_8 ) ) {
            ZipEntry entry = zin.getNextEntry();
            while ( entry != null && !pending.isEmpty() ) {
                final Deque< PlannedAttachment > queue = pending.get( entry.getName() );
                if ( queue != null ) {
                    final PlannedAttachment a = queue.poll();
                    if ( queue.isEmpty() ) {
                        pending.remove( entry.getName() );
                    }
                    importAttachment( new PlannedEntryStream( zin, raw, a.size() ), a, created );
                }
                entry = zin.getNextEntry();
            }
        }
        for ( final Deque< PlannedAttachment > queue : pending.values() ) {
            for ( final PlannedAttachment a : queue ) {
                record( "attachment", a.owner() + "/" + a.fileName(), a.vaultPath(), ItemStatus.FAILED,
                    "file missing from archive: " + a.entryName(), List.of() );
                tick();
            }
        }
    }

    private void importAttachment( final PlannedEntryStream in, final PlannedAttachment a, final Set< String > created ) {
        final String name = a.owner() + "/" + a.fileName();
        checkNotInterrupted();
        setCurrent( name );
        final String rejection = sink.attachmentRejection( a.fileName(), a.size() ).orElse( null );
        if ( !created.contains( a.owner() ) ) {
            record( "attachment", name, a.vaultPath(), ItemStatus.FAILED, "owner page " + a.owner() + " was not created", List.of() );
        } else if ( rejection != null ) {
            record( "attachment", name, a.vaultPath(), ItemStatus.FAILED, "refused by attachment policy: " + rejection, List.of() );
        } else {
            storeAttachment( in, a, name );
        }
        tick();
    }

    private void storeAttachment( final PlannedEntryStream in, final PlannedAttachment a, final String name ) {
        try {
            sink.storeAttachment( a.owner(), a.fileName(), in, author );
            record( "attachment", name, a.vaultPath(), ItemStatus.CREATED, null, List.of() );
        } catch ( final Exception e ) {
            final String reason = in.violation() != null ? in.violation() : String.valueOf( e.getMessage() );
            LOG.warn( "Obsidian import {}: attachment {} failed: {}", id, name, reason, e );
            record( "attachment", name, a.vaultPath(), ItemStatus.FAILED, reason, List.of() );
        }
    }

    /**
     * The current zip entry, refusing to yield more than the planned size or to inflate past the zip-bomb ratio.
     * Closing it does not close the zip stream (the sink may close what it is given).
     */
    private static final class PlannedEntryStream extends FilterInputStream {

        private final CountingInputStream raw;
        private final long rawStart;
        private final long planned;
        private long count;
        private String violation;

        PlannedEntryStream( final ZipInputStream zin, final CountingInputStream raw, final long planned ) {
            super( zin );
            this.raw = raw;
            this.rawStart = raw.count();
            this.planned = planned;
        }

        /** Why reading was refused, or null. */
        String violation() {
            return violation;
        }

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[ 1 ];
            final int n = read( one, 0, 1 );
            return n < 0 ? -1 : one[ 0 ] & 0xFF;
        }

        @Override
        public int read( final byte[] b, final int off, final int len ) throws IOException {
            if ( len == 0 ) {
                return 0;
            }
            // Ask for at most one byte past the plan, so an overrun is detected without handing it on.
            final int n = super.read( b, off, ( int ) Math.min( len, planned - count + 1 ) );
            if ( n > 0 ) {
                count += n;
                if ( count > planned ) {
                    throw refuse( "size differs from plan (more than " + planned + " bytes)" );
                }
                if ( VaultArchiveReader.exceedsRatio( count, raw.count() - rawStart ) ) {
                    throw refuse( "expands more than 100:1 (possible zip bomb)" );
                }
            }
            return n;
        }

        private IOException refuse( final String why ) {
            violation = why;
            return new IOException( why );
        }

        @Override
        public long skip( final long n ) throws IOException {
            final byte[] buf = new byte[ 8192 ];
            long skipped = 0;
            while ( skipped < n ) {
                final int r = read( buf, 0, ( int ) Math.min( buf.length, n - skipped ) );
                if ( r < 0 ) {
                    break;
                }
                skipped += r;
            }
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void close() {
            // The zip stream stays open for the next entry.
        }
    }

    // ---- state -----------------------------------------------------------------------------

    private synchronized void record( final String kind, final String name, final String vaultPath,
                                      final ItemStatus status, final String reason, final List< String > warnings ) {
        results.add( new ItemResult( kind, name, vaultPath, status, reason, List.copyOf( warnings ) ) );
    }

    private synchronized void tick() {
        done++;
    }

    private synchronized void setCurrent( final String name ) {
        current = name;
    }

    private synchronized void finish( final JobState finalState, final String msg ) {
        state = finalState;
        message = msg;
        current = null;
        finishedAt = clock.instant();
        plan = null;
    }

    private long count( final String kind, final ItemStatus status ) {
        return results.stream().filter( r -> r.kind().equals( kind ) && r.status() == status ).count();
    }

    private Map< String, Object > summary() {
        final Map< String, Object > s = new LinkedHashMap<>();
        s.put( "pagesCreated", count( "page", ItemStatus.CREATED ) );
        s.put( "pagesSkipped", count( "page", ItemStatus.SKIPPED_EXISTS ) + count( "page", ItemStatus.SKIPPED_RESERVED ) );
        s.put( "pagesFailed", count( "page", ItemStatus.FAILED ) );
        s.put( "attachmentsCreated", count( "attachment", ItemStatus.CREATED ) );
        s.put( "attachmentsFailed", count( "attachment", ItemStatus.FAILED ) );
        s.put( "unreferencedFiles", unreferencedFiles );
        final Set< String > created = new HashSet<>();
        results.stream().filter( r -> r.kind().equals( "page" ) && r.status() == ItemStatus.CREATED )
            .forEach( r -> created.add( r.name() ) );
        s.put( "hubs", hubDrafts.stream().filter( created::contains ).toList() );
        return s;
    }
}

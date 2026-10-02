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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Applies a reviewed {@link PlanResult}: hubs and notes first (the plan orders drafts hub-first), then
 * attachments streamed from the spooled zip. Never overwrites; per-item errors never fail the job.
 */
public final class VaultImportJob implements Runnable {

    private static final Logger LOG = LogManager.getLogger( VaultImportJob.class );

    private final String id;
    private final String owner;
    private final String author;
    private final SpooledUpload upload;
    private final PlanResult plan;
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
        } catch ( final IOException | RuntimeException e ) {
            LOG.warn( "Obsidian import job {} failed: {}", id, e.getMessage(), e );
            finish( JobState.FAILED, e.getMessage() );
        } finally {
            discardUpload();
        }
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
        try ( ZipFile zip = new ZipFile( upload.file().toFile(), StandardCharsets.UTF_8 ) ) {
            for ( final PlannedAttachment a : plan.attachmentsToImport() ) {
                importAttachment( zip, a, created );
            }
        }
    }

    private void importAttachment( final ZipFile zip, final PlannedAttachment a, final Set< String > created ) {
        final String name = a.owner() + "/" + a.fileName();
        setCurrent( name );
        if ( !created.contains( a.owner() ) ) {
            record( "attachment", name, a.vaultPath(), ItemStatus.FAILED, "owner page " + a.owner() + " was not created", List.of() );
        } else {
            final ZipEntry entry = zip.getEntry( a.entryName() );
            if ( entry == null ) {
                record( "attachment", name, a.vaultPath(), ItemStatus.FAILED, "file missing from archive: " + a.entryName(), List.of() );
            } else {
                storeAttachment( zip, entry, a, name );
            }
        }
        tick();
    }

    private void storeAttachment( final ZipFile zip, final ZipEntry entry, final PlannedAttachment a, final String name ) {
        try ( InputStream in = zip.getInputStream( entry ) ) {
            sink.storeAttachment( a.owner(), a.fileName(), in, author );
            record( "attachment", name, a.vaultPath(), ItemStatus.CREATED, null, List.of() );
        } catch ( final Exception e ) {
            LOG.warn( "Obsidian import {}: attachment {} failed: {}", id, name, e.getMessage(), e );
            record( "attachment", name, a.vaultPath(), ItemStatus.FAILED, String.valueOf( e.getMessage() ), List.of() );
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
        finishedAt = Instant.now();
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
        s.put( "unreferencedFiles", plan.plan().totals().attachmentsSkipped() );
        final Set< String > created = new HashSet<>();
        results.stream().filter( r -> r.kind().equals( "page" ) && r.status() == ItemStatus.CREATED )
            .forEach( r -> created.add( r.name() ) );
        s.put( "hubs", plan.drafts().stream().filter( d -> d.hub() && created.contains( d.name() ) )
            .map( PageDraft::name ).toList() );
        return s;
    }
}

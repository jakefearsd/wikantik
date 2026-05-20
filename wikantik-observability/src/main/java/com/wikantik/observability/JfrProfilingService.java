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
package com.wikantik.observability;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Start/stop/list/download of Java Flight Recorder recordings, gated by the
 * {@code /admin/profiling/jfr/*} REST endpoint. Default-off — no recording
 * active at boot; nothing is allocated until {@link #start} is hit.
 *
 * <p>One concurrent recording at a time: a second {@code start} call while
 * a recording is running throws {@link IllegalStateException} so the REST
 * layer can map it to a 409. Duration is bounded by
 * {@link #MAX_DURATION_SECONDS} so an unattended recording can't run forever.
 * Directory size is capped to refuse new recordings when the operator has
 * not cleaned up old captures.</p>
 *
 * <p>Uses JFR's built-in {@code default} configuration (~1 % CPU overhead).
 * A future toggle could expose the {@code profile} configuration; out of
 * scope for v1.</p>
 */
public final class JfrProfilingService {

    private static final Logger LOG = LogManager.getLogger( JfrProfilingService.class );

    /** Minimum duration accepted by {@link #start}, in seconds. */
    public static final int MIN_DURATION_SECONDS = 1;
    /** Maximum duration accepted by {@link #start}, in seconds. */
    public static final int MAX_DURATION_SECONDS = 600;

    private final Path directory;
    private final long maxDirectoryBytes;
    private final ConcurrentHashMap< String, Tracked > recordings = new ConcurrentHashMap<>();
    /** Single-recording guard. */
    private volatile String runningId;

    public JfrProfilingService( final Path directory, final long maxDirectoryBytes ) {
        if ( directory == null ) throw new IllegalArgumentException( "directory must not be null" );
        if ( maxDirectoryBytes <= 0 ) throw new IllegalArgumentException( "maxDirectoryBytes must be positive" );
        this.directory = directory;
        this.maxDirectoryBytes = maxDirectoryBytes;
    }

    public synchronized RecordingInfo start( final int durationSeconds, final String label ) {
        if ( durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS ) {
            throw new IllegalArgumentException(
                "duration_s must be in [" + MIN_DURATION_SECONDS + ".." + MAX_DURATION_SECONDS + "]" );
        }
        if ( runningId != null ) {
            // The JFR engine auto-stops a recording when its duration timer
            // fires, but the engine doesn't call our stop() — so runningId
            // could be pointing at a recording that's no longer running.
            // Lazy-finalise it here on the next start() rather than refusing
            // with 409. A dedicated FlightRecorderListener would be cleaner
            // but adds JFR API surface for what is effectively a state-sync.
            final Tracked stale = recordings.get( runningId );
            final RecordingState staleState = stale == null ? null : stale.recording.getState();
            // Recording goes to STOPPED if no destination was set, CLOSED when
            // the destination file has been written (which is our case — every
            // recording has setDestination()). Treat both as "not running".
            if ( staleState == RecordingState.STOPPED || staleState == RecordingState.CLOSED ) {
                finaliseStopped( runningId, stale );
            } else {
                throw new IllegalStateException(
                    "A recording is already running (recording_id=" + runningId + ")" );
            }
        }
        ensureDirectory();
        enforceSizeCap();

        final String safeLabel = label == null ? "" : label.replaceAll( "[^a-zA-Z0-9._-]", "_" );
        final String id = UUID.randomUUID().toString();
        // Timestamp uses ISO-8601 with the ':' separators replaced — those are
        // illegal in file names on some filesystems. Resulting filename is
        // safe on Linux, macOS, and Windows.
        final String fileName = "wikantik-" + Instant.now().toString().replace( ':', '-' )
            + ( safeLabel.isEmpty() ? "" : "-" + safeLabel ) + ".jfr";
        final Path filePath = directory.resolve( fileName );

        final Recording rec;
        try {
            rec = new Recording( Configuration.getConfiguration( "default" ) );
        } catch ( final Exception e ) {
            throw new IllegalStateException( "JFR default config unavailable: " + e.getMessage(), e );
        }
        rec.setName( "wikantik-" + id );
        rec.setDuration( Duration.ofSeconds( durationSeconds ) );
        try {
            rec.setDestination( filePath );
        } catch ( final IOException e ) {
            rec.close();
            throw new IllegalStateException( "Cannot set JFR destination " + filePath + ": " + e.getMessage(), e );
        }
        rec.start();
        runningId = id;
        final RecordingInfo info = new RecordingInfo(
            id, Instant.now(), durationSeconds, safeLabel, filePath, 0L, "RUNNING" );
        recordings.put( id, new Tracked( rec, info ) );
        LOG.info( "JFR start: id={} duration={}s label={} file={}", id, durationSeconds, safeLabel, filePath );
        return info;
    }

    public synchronized RecordingInfo stop( final String recordingId ) {
        if ( recordingId == null ) throw new IllegalArgumentException( "recording_id must not be null" );
        final Tracked t = recordings.get( recordingId );
        if ( t == null ) throw new IllegalArgumentException( "Unknown recording_id: " + recordingId );
        return finaliseStopped( recordingId, t );
    }

    /**
     * Close out a recording and publish its {@code FINISHED} info, idempotent on
     * the JFR side (the recording may already be stopped by its duration timer).
     * Used by both the public {@link #stop(String)} entry point and the lazy
     * auto-cleanup inside {@link #start(int, String)}.
     */
    private RecordingInfo finaliseStopped( final String recordingId, final Tracked t ) {
        try {
            t.recording.stop();
        } catch ( final IllegalStateException ignore ) {
            // Already stopped by the duration timer — fine; idempotent stop.
            LOG.debug( "JFR stop on already-stopped recording id={}", recordingId );
        }
        t.recording.close();
        if ( recordingId.equals( runningId ) ) {
            runningId = null;
        }
        final long size = t.info.filePath().toFile().length();
        final RecordingInfo finished = new RecordingInfo(
            t.info.recordingId(), t.info.startTime(), t.info.durationSeconds(),
            t.info.label(), t.info.filePath(), size, "FINISHED" );
        recordings.put( recordingId, new Tracked( t.recording, finished ) );
        LOG.info( "JFR stop: id={} size={}B", recordingId, size );
        return finished;
    }

    public List< RecordingInfo > list() {
        final List< RecordingInfo > out = new ArrayList<>( recordings.size() );
        for ( final Tracked t : recordings.values() ) out.add( t.info );
        return List.copyOf( out );
    }

    public RecordingInfo get( final String recordingId ) {
        final Tracked t = recordings.get( recordingId );
        return t == null ? null : t.info;
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories( directory );
        } catch ( final IOException e ) {
            throw new IllegalStateException( "Cannot create profiling dir " + directory + ": " + e.getMessage(), e );
        }
    }

    private void enforceSizeCap() {
        try ( var stream = Files.list( directory ) ) {
            final long total = stream
                .filter( p -> p.toString().endsWith( ".jfr" ) )
                .mapToLong( p -> p.toFile().length() )
                .sum();
            if ( total > maxDirectoryBytes ) {
                throw new IllegalStateException(
                    "Profiling directory exceeds size cap (" + total + " > " + maxDirectoryBytes
                    + " bytes). Delete old recordings before starting a new one." );
            }
        } catch ( final IOException e ) {
            throw new IllegalStateException( "Cannot enforce profiling dir size cap: " + e.getMessage(), e );
        }
    }

    /** Immutable DTO. The {@code status} string is one of "RUNNING", "FINISHED", or "FAILED". */
    public record RecordingInfo(
        String recordingId,
        Instant startTime,
        int durationSeconds,
        String label,
        Path filePath,
        long sizeBytes,
        String status
    ) {}

    private record Tracked( Recording recording, RecordingInfo info ) {}
}

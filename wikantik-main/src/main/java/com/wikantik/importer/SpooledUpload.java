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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * An upload streamed to a temp file with a running SHA-256 and a hard byte cap.
 *
 * @param file         the temp file holding the bytes
 * @param sha256       lowercase hex SHA-256 of the content
 * @param size         number of bytes spooled
 * @param originalName the client-supplied file name
 */
public record SpooledUpload( Path file, String sha256, long size, String originalName ) {

    private static final Logger LOG = LogManager.getLogger( SpooledUpload.class );
    private static final String TEMP_PREFIX = "wikantik-import-";

    /** The JVM temp directory, where uploads are spooled unless a directory is given. */
    public static Path defaultDir() {
        return Path.of( System.getProperty( "java.io.tmpdir" ) );
    }

    /**
     * Deletes {@code wikantik-import-*} spool files in {@code dir} last modified more than {@code maxAge} ago
     * (left behind when a JVM died mid-import). A file that cannot be deleted is logged and skipped.
     *
     * @return the number of files deleted
     */
    public static int sweepStale( final Path dir, final java.time.Duration maxAge ) {
        final java.time.Instant cutoff = java.time.Instant.now().minus( maxAge );
        int deleted = 0;
        try ( java.nio.file.DirectoryStream< Path > ds = Files.newDirectoryStream( dir, TEMP_PREFIX + "*" ) ) {
            for ( final Path p : ds ) {
                if ( isStale( p, cutoff ) && deleteIfPresent( p ) ) {
                    deleted++;
                }
            }
        } catch ( final IOException e ) {
            LOG.warn( "Could not scan {} for stale import spool files: {}", dir, e.getMessage(), e );
        }
        return deleted;
    }

    private static boolean isStale( final Path p, final java.time.Instant cutoff ) {
        try {
            return Files.isRegularFile( p ) && Files.getLastModifiedTime( p ).toInstant().isBefore( cutoff );
        } catch ( final IOException e ) {
            LOG.warn( "Could not stat import spool file {}: {}", p, e.getMessage(), e );
            return false;
        }
    }

    private static boolean deleteIfPresent( final Path p ) {
        try {
            return Files.deleteIfExists( p );
        } catch ( final IOException e ) {
            LOG.warn( "Could not delete stale import spool file {}: {}", p, e.getMessage(), e );
            return false;
        }
    }

    /**
     * Copies {@code in} to a temp file, refusing more than {@code maxBytes}.
     *
     * @throws ImportLimitException if the stream exceeds {@code maxBytes} (the temp file is removed)
     * @throws IOException          on a read/write failure (the temp file is removed)
     */
    public static SpooledUpload spool( final InputStream in, final String originalName, final long maxBytes )
            throws IOException, ImportLimitException {
        return spool( defaultDir(), in, originalName, maxBytes );
    }

    /** As {@link #spool(InputStream, String, long)} but the temp file is created in {@code dir}. */
    public static SpooledUpload spool( final Path dir, final InputStream in, final String originalName, final long maxBytes )
            throws IOException, ImportLimitException {
        final MessageDigest digest = newDigest();
        final Path tmp = Files.createTempFile( dir, TEMP_PREFIX, ".zip" );
        try {
            final long count = copyCapped( new DigestInputStream( in, digest ), tmp, maxBytes );
            return new SpooledUpload( tmp, HexFormat.of().formatHex( digest.digest() ), count, originalName );
        } catch ( final IOException | ImportLimitException | RuntimeException e ) {
            deleteQuietly( tmp );
            throw e;
        }
    }

    private static long copyCapped( final InputStream in, final Path target, final long maxBytes )
            throws IOException, ImportLimitException {
        final byte[] buf = new byte[ 8192 ];
        long count = 0;
        try ( var out = Files.newOutputStream( target ) ) {
            int n;
            while ( ( n = in.read( buf ) ) != -1 ) {
                count += n;
                if ( count > maxBytes ) {
                    throw new ImportLimitException( ImportLimits.PROP_MAX_UPLOAD_BYTES, maxBytes, "upload" );
                }
                out.write( buf, 0, n );
            }
        }
        return count;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance( "SHA-256" );
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );
        }
    }

    private static void deleteQuietly( final Path p ) {
        try {
            Files.deleteIfExists( p );
        } catch ( final IOException e ) {
            LOG.warn( "Could not delete import spool file {}: {}", p, e.getMessage(), e );
        }
    }

    /** Removes the temp file. Idempotent; a failure is logged, not thrown. */
    public void delete() {
        deleteQuietly( file );
    }
}

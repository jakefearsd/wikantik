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
package com.wikantik.export;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Streams an Obsidian vault into a zip as pages and attachments are converted, then appends the
 * generated readme and {@code .wikantik/manifest.json} companion when the export completes.
 *
 * <p>{@link #close()} calls {@link ZipOutputStream#finish()} only when {@link #finish} has not
 * already been called — it never closes the underlying {@link OutputStream}, because that stream
 * (typically an HTTP response body) is owned by the servlet container, not by this writer.</p>
 */
public final class ObsidianVaultWriter implements Closeable {
    public static final String README_PATH = "Wikantik Export.md";
    public static final String MANIFEST_PATH = ".wikantik/manifest.json";

    private static final Logger LOG = LogManager.getLogger( ObsidianVaultWriter.class );

    private final ZipOutputStream zip;
    private final Set< String > written = new HashSet<>();
    private boolean finished;

    public ObsidianVaultWriter( final OutputStream out ) {
        this.zip = new ZipOutputStream( out, StandardCharsets.UTF_8 );
    }

    /** Writes the entry and returns its lowercase hex SHA-256. */
    public String writeText( final String path, final String content ) throws IOException {
        return writeStream( path, new ByteArrayInputStream( content.getBytes( StandardCharsets.UTF_8 ) ) );
    }

    public String writeStream( final String path, final InputStream in ) throws IOException {
        requireSafeEntryName( path );
        if ( !written.add( path ) ) {
            throw new IllegalStateException( "duplicate zip entry: " + path );
        }
        final MessageDigest digest = sha256();
        zip.putNextEntry( new ZipEntry( path ) );
        // Deliberately not try-with-resources: DigestOutputStream#close() delegates to the wrapped
        // stream's close(), which would finish/close the whole zip after a single entry.
        final DigestOutputStream digestOut = new DigestOutputStream( zip, digest );
        try {
            in.transferTo( digestOut );
            digestOut.flush();
        } finally {
            zip.closeEntry();
        }
        return HexFormat.of().formatHex( digest.digest() );
    }

    /** Writes readme + manifest and finishes the zip (does not close the underlying stream). */
    public void finish( final ExportManifest manifest, final String readme ) throws IOException {
        writeText( README_PATH, readme );
        writeText( MANIFEST_PATH, manifest.toJson() );
        zip.finish();
        finished = true;
    }

    /** Finishes the zip if {@link #finish} was not already called; never closes the underlying stream. */
    @Override
    public void close() throws IOException {
        if ( !finished ) {
            try {
                zip.finish();
            } catch ( final IOException e ) {
                LOG.warn( "failed to finish Obsidian export zip on close", e );
                throw e;
            } finally {
                finished = true;
            }
        }
    }

    /**
     * Zip-slip invariant: an entry name must be relative, {@code /}-separated and free of
     * {@code .}, {@code ..} and empty segments, so extracting the vault can never write outside
     * its root. {@link VaultLayout} never produces such a name; this is the backstop.
     *
     * @throws IllegalArgumentException if {@code path} violates the invariant
     */
    static void requireSafeEntryName( final String path ) {
        if ( path == null || path.isEmpty() || path.startsWith( "/" ) || path.indexOf( '\\' ) >= 0 ) {
            throw new IllegalArgumentException( "unsafe zip entry name: " + path );
        }
        for ( final String segment : path.split( "/", -1 ) ) {
            if ( segment.isEmpty() || ".".equals( segment ) || "..".equals( segment ) ) {
                throw new IllegalArgumentException( "unsafe zip entry name: " + path );
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance( "SHA-256" );
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 not available", e );
        }
    }
}

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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads an uploaded Obsidian vault zip into memory-bounded {@link VaultNote}s and {@link VaultFile}s.
 *
 * <p>Only {@link ZipInputStream} is used, so local headers are the single source of truth. Nothing is ever
 * written to disk, so zip-slip cannot escape; unsafe names are rejected anyway. The compression ratio is
 * measured from a byte-counting stream beneath the zip stream, never from header-declared sizes. Only
 * markdown notes within the per-page byte limit are buffered; every other entry is drained and counted.</p>
 */
public final class VaultArchiveReader {

    private static final Logger LOG = LogManager.getLogger( VaultArchiveReader.class );

    private static final long RATIO_FLOOR_BYTES = 1_048_576L;
    private static final long MAX_RATIO = 100L;

    private final ImportLimits limits;

    public VaultArchiveReader( final ImportLimits limits ) {
        this.limits = limits;
    }

    /**
     * Reads the zip.
     *
     * @throws VaultArchiveException for a malformed zip, unsafe/non-UTF-8 entry name or a zip bomb
     * @throws ImportLimitException  when the entry count or total uncompressed size limit is exceeded
     */
    public VaultArchive read( final Path zip ) throws IOException, VaultArchiveException, ImportLimitException {
        final List< String > names = readNames( zip );
        for ( final String name : names ) {
            VaultEntryNames.requireSafe( name );
        }
        final String prefix = VaultEntryNames.wrapperPrefix( names );
        return readData( zip, prefix );
    }

    // ---- pass 1 ---------------------------------------------------------------------------------

    private List< String > readNames( final Path zip ) throws IOException, VaultArchiveException, ImportLimitException {
        final List< String > names = new ArrayList<>();
        final byte[] buf = new byte[ 8192 ];
        long total = 0;
        try ( CountingInputStream raw = new CountingInputStream( Files.newInputStream( zip ) );
              ZipInputStream zin = new ZipInputStream( raw, StandardCharsets.UTF_8 ) ) {
            ZipEntry entry = zin.getNextEntry();
            while ( entry != null ) {
                names.add( entry.getName() );
                if ( names.size() > limits.maxEntries() ) {
                    throw new ImportLimitException( ImportLimits.PROP_MAX_ENTRIES, limits.maxEntries(), "zip entry count" );
                }
                // Drain under the same caps as pass 2 so a bomb is stopped before it is fully inflated.
                total += drain( zin, raw, entry.getName(), false, total, buf ).size();
                entry = zin.getNextEntry();
            }
        } catch ( final IllegalArgumentException e ) {
            throw badEncoding( e );
        } catch ( final ZipException e ) {
            throw malformed( e );
        }
        if ( names.isEmpty() && !startsWithPk( zip ) ) {
            throw new VaultArchiveException( "not a zip archive" );
        }
        return names;
    }

    private static boolean startsWithPk( final Path zip ) throws IOException {
        try ( InputStream in = Files.newInputStream( zip ) ) {
            return in.read() == 'P' && in.read() == 'K';
        }
    }

    private static VaultArchiveException badEncoding( final IllegalArgumentException e ) {
        LOG.warn( "vault zip has a non-UTF-8 entry name: {}", e.getMessage(), e );
        return new VaultArchiveException( "zip entry name is not valid UTF-8 (CP437 or another legacy encoding?)"
                                          + " — re-zip the vault with a UTF-8 aware tool", e );
    }

    /** ZipInputStream reports an undecodable entry name as a ZipException caused by IllegalArgumentException. */
    private static VaultArchiveException malformed( final ZipException e ) {
        if ( e.getCause() instanceof IllegalArgumentException iae ) {
            return badEncoding( iae );
        }
        LOG.warn( "malformed vault zip: {}", e.getMessage(), e );
        return new VaultArchiveException( "malformed zip: " + e.getMessage(), e );
    }

    // ---- pass 2 ---------------------------------------------------------------------------------

    private VaultArchive readData( final Path zip, final String prefix )
            throws IOException, VaultArchiveException, ImportLimitException {
        final List< VaultNote > notes = new ArrayList<>();
        final List< VaultFile > files = new ArrayList<>();
        int ignoredCount = 0;
        long total = 0;
        final byte[] buf = new byte[ 8192 ];
        try ( CountingInputStream raw = new CountingInputStream( Files.newInputStream( zip ) );
              ZipInputStream zin = new ZipInputStream( raw, StandardCharsets.UTF_8 ) ) {
            ZipEntry entry = zin.getNextEntry();
            while ( entry != null ) {
                final String name = entry.getName();
                final String rel = VaultEntryNames.relative( name, prefix );
                final boolean skip = VaultEntryNames.ignored( name, rel );
                final boolean keepText = !skip && VaultPaths.isNote( rel );
                final Drained d = drain( zin, raw, name, keepText, total, buf );
                total += d.size();
                if ( skip ) {
                    ignoredCount++;
                } else if ( keepText ) {
                    notes.add( new VaultNote( rel, d.text(), d.size() ) );
                } else {
                    files.add( new VaultFile( rel, name, d.size() ) );
                }
                entry = zin.getNextEntry();
            }
        } catch ( final ZipException e ) {
            throw malformed( e );
        } catch ( final IllegalArgumentException e ) {
            throw badEncoding( e );
        }
        notes.sort( ( a, b ) -> VaultPaths.ORDER.compare( a.path(), b.path() ) );
        files.sort( ( a, b ) -> VaultPaths.ORDER.compare( a.path(), b.path() ) );
        return new VaultArchive( List.copyOf( notes ), List.copyOf( files ), ignoredCount );
    }

    private record Drained( long size, String text ) {
    }

    private Drained drain( final ZipInputStream zin, final CountingInputStream raw, final String name,
                           final boolean keepText, final long totalBefore, final byte[] buf )
            throws IOException, VaultArchiveException, ImportLimitException {
        final long rawBefore = raw.count();
        final ByteArrayOutputStream sink = keepText ? new ByteArrayOutputStream() : null;
        boolean buffering = keepText;
        long entryBytes = 0;
        int n = zin.read( buf );
        while ( n >= 0 ) {
            entryBytes += n;
            checkLimits( name, entryBytes, totalBefore + entryBytes, raw.count() - rawBefore );
            buffering = buffering && entryBytes <= limits.maxPageBytes();
            if ( buffering ) {
                sink.write( buf, 0, n );
            }
            n = zin.read( buf );
        }
        return new Drained( entryBytes, buffering ? decode( sink.toByteArray() ) : null );
    }

    private void checkLimits( final String name, final long entryBytes, final long total, final long rawBytes )
            throws VaultArchiveException, ImportLimitException {
        if ( total > limits.maxUncompressedBytes() ) {
            throw new ImportLimitException( ImportLimits.PROP_MAX_UNCOMPRESSED_BYTES,
                                            limits.maxUncompressedBytes(), "uncompressed vault" );
        }
        if ( entryBytes > RATIO_FLOOR_BYTES && entryBytes > MAX_RATIO * Math.max( 1, rawBytes ) ) {
            throw new VaultArchiveException( "zip entry '" + name + "' expands more than 100:1 (zip bomb?)" );
        }
    }

    private static String decode( final byte[] bytes ) {
        final String text = new String( bytes, StandardCharsets.UTF_8 );
        return !text.isEmpty() && text.charAt( 0 ) == '﻿' ? text.substring( 1 ) : text;
    }
}

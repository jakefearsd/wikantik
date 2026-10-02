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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds in-memory vault zips for importer tests. Shipped in the wikantik-main test-jar. */
public final class TestVaults {

    private TestVaults() {
    }

    /** A DEFLATED zip with the entries in insertion order. */
    public static byte[] zip( final Map< String, byte[] > entries ) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try ( ZipOutputStream zos = new ZipOutputStream( out, StandardCharsets.UTF_8 ) ) {
            for ( final Map.Entry< String, byte[] > e : entries.entrySet() ) {
                zos.putNextEntry( new ZipEntry( e.getKey() ) );
                zos.write( e.getValue() );
                zos.closeEntry();
            }
        } catch ( final IOException e ) {
            throw new UncheckedIOException( e );
        }
        return out.toByteArray();
    }

    /** UTF-8 convenience over {@link #zip(Map)}. */
    public static byte[] zipText( final Map< String, String > entries ) {
        final Map< String, byte[] > bytes = new LinkedHashMap<>();
        entries.forEach( ( k, v ) -> bytes.put( k, v.getBytes( StandardCharsets.UTF_8 ) ) );
        return zip( bytes );
    }

    /** A single STORED entry whose name is the given raw bytes (general-purpose flag bit 11 clear). */
    public static byte[] zipWithRawName( final byte[] nameBytes, final byte[] data ) {
        final CRC32 crc = new CRC32();
        crc.update( data );
        final ByteBuffer lfh = header( 30, 0x04034b50 );
        lfh.putShort( ( short ) 10 ).putShort( ( short ) 0 ).putShort( ( short ) 0 ).putInt( 0 );
        lfh.putInt( ( int ) crc.getValue() ).putInt( data.length ).putInt( data.length );
        lfh.putShort( ( short ) nameBytes.length ).putShort( ( short ) 0 );
        final ByteBuffer cdh = header( 46, 0x02014b50 );
        cdh.putShort( ( short ) 20 ).putShort( ( short ) 10 ).putShort( ( short ) 0 ).putShort( ( short ) 0 );
        cdh.putInt( 0 ).putInt( ( int ) crc.getValue() ).putInt( data.length ).putInt( data.length );
        cdh.putShort( ( short ) nameBytes.length ).putShort( ( short ) 0 ).putShort( ( short ) 0 );
        cdh.putShort( ( short ) 0 ).putShort( ( short ) 0 ).putInt( 0 ).putInt( 0 );
        final int cdOffset = 30 + nameBytes.length + data.length;
        final int cdSize = 46 + nameBytes.length;
        final ByteBuffer eocd = header( 22, 0x06054b50 );
        eocd.putShort( ( short ) 0 ).putShort( ( short ) 0 ).putShort( ( short ) 1 ).putShort( ( short ) 1 );
        eocd.putInt( cdSize ).putInt( cdOffset ).putShort( ( short ) 0 );
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes( lfh.array() );
        out.writeBytes( nameBytes );
        out.writeBytes( data );
        out.writeBytes( cdh.array() );
        out.writeBytes( nameBytes );
        out.writeBytes( eocd.array() );
        return out.toByteArray();
    }

    /**
     * A zip whose local headers and central directory disagree. Streaming readers ({@code ZipInputStream}) see
     * {@code note} and a STORED {@code file} holding {@code visible}, then stop at padding. The central directory (what
     * {@code java.util.zip.ZipFile} trusts) points {@code file} at a hidden DEFLATED local entry, placed after the
     * padding, that inflates to {@code hidden}.
     */
    public static byte[] zipWithHiddenCentralEntry( final String note, final byte[] noteData, final String file,
                                                    final byte[] visible, final byte[] hidden ) {
        final byte[] deflated = rawDeflate( hidden );
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int noteOff = out.size();
        writeLoc( out, note, 0, noteData, noteData, noteData.length );
        writeLoc( out, file, 0, visible, visible, visible.length );
        out.writeBytes( new byte[ 16 ] );   // not a local-header signature: a streaming reader stops here
        final int hiddenOff = out.size();
        writeLoc( out, file, 8, deflated, hidden, deflated.length );
        final int cdOff = out.size();
        writeCen( out, note, 0, noteData, noteData.length, noteOff );
        writeCen( out, file, 8, hidden, deflated.length, hiddenOff );
        final int cdSize = out.size() - cdOff;
        final ByteBuffer eocd = header( 22, 0x06054b50 );
        eocd.putShort( ( short ) 0 ).putShort( ( short ) 0 ).putShort( ( short ) 2 ).putShort( ( short ) 2 );
        eocd.putInt( cdSize ).putInt( cdOff ).putShort( ( short ) 0 );
        out.writeBytes( eocd.array() );
        return out.toByteArray();
    }

    private static byte[] rawDeflate( final byte[] data ) {
        final java.util.zip.Deflater d = new java.util.zip.Deflater( java.util.zip.Deflater.BEST_COMPRESSION, true );
        d.setInput( data );
        d.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[ 65536 ];
        while ( !d.finished() ) {
            out.write( buf, 0, d.deflate( buf ) );
        }
        d.end();
        return out.toByteArray();
    }

    private static long crc( final byte[] data ) {
        final CRC32 crc = new CRC32();
        crc.update( data );
        return crc.getValue();
    }

    private static void writeLoc( final ByteArrayOutputStream out, final String name, final int method,
                                  final byte[] stored, final byte[] plain, final int csize ) {
        final byte[] n = name.getBytes( StandardCharsets.UTF_8 );
        final ByteBuffer lfh = header( 30, 0x04034b50 );
        lfh.putShort( ( short ) 20 ).putShort( ( short ) 0x0800 ).putShort( ( short ) method ).putInt( 0 );
        lfh.putInt( ( int ) crc( plain ) ).putInt( csize ).putInt( plain.length );
        lfh.putShort( ( short ) n.length ).putShort( ( short ) 0 );
        out.writeBytes( lfh.array() );
        out.writeBytes( n );
        out.writeBytes( stored );
    }

    private static void writeCen( final ByteArrayOutputStream out, final String name, final int method,
                                  final byte[] plain, final int csize, final int locOffset ) {
        final byte[] n = name.getBytes( StandardCharsets.UTF_8 );
        final ByteBuffer cdh = header( 46, 0x02014b50 );
        cdh.putShort( ( short ) 20 ).putShort( ( short ) 20 ).putShort( ( short ) 0x0800 ).putShort( ( short ) method );
        cdh.putInt( 0 ).putInt( ( int ) crc( plain ) ).putInt( csize ).putInt( plain.length );
        cdh.putShort( ( short ) n.length ).putShort( ( short ) 0 ).putShort( ( short ) 0 );
        cdh.putShort( ( short ) 0 ).putShort( ( short ) 0 ).putInt( 0 ).putInt( locOffset );
        out.writeBytes( cdh.array() );
        out.writeBytes( n );
    }

    private static ByteBuffer header( final int size, final int signature ) {
        final ByteBuffer b = ByteBuffer.allocate( size ).order( ByteOrder.LITTLE_ENDIAN );
        b.putInt( signature );
        return b;
    }

    /** Writes the zip to a temp file; the caller deletes it. */
    public static Path write( final byte[] zip ) throws IOException {
        final Path p = Files.createTempFile( "wikantik-vault-test-", ".zip" );
        Files.write( p, zip );
        return p;
    }

    /** Spools the zip through {@link SpooledUpload}; the caller deletes {@code file()}. */
    public static SpooledUpload upload( final byte[] zip, final String name ) throws Exception {
        try ( InputStream in = new java.io.ByteArrayInputStream( zip ) ) {
            return SpooledUpload.spool( in, name, Long.MAX_VALUE );
        }
    }

    private static final String FIXTURE_ROOT = "/com/wikantik/importer/fixture-vault/";
    private static final byte[] PNG = { ( byte ) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
    /** Explicit list (not a directory walk) so it also works when the fixture is read from the test-jar. */
    private static final java.util.List< String > FIXTURE_FILES = java.util.List.of(
        ".obsidian/app.json", "Wikantik Export.md", "Welcome.md", "Main.md", "Projects/Projects.md",
        "Projects/Alpha.md", "Projects/Beta v2!.md", "Projects/Deep/Deeper/Gamma.md",
        "Projects/assets/evil.svg", "Notes/Daily.md", "Notes/Code.md" );

    /** The shared fixture vault (path to bytes), plus two PNGs added in code so no binary is committed. */
    public static Map< String, byte[] > fixture() {
        final Map< String, byte[] > vault = new LinkedHashMap<>();
        for ( final String path : FIXTURE_FILES ) {
            try ( InputStream in = TestVaults.class.getResourceAsStream( FIXTURE_ROOT + path ) ) {
                if ( in == null ) {
                    throw new IllegalStateException( "missing fixture resource " + path );
                }
                vault.put( path, in.readAllBytes() );
            } catch ( final IOException e ) {
                throw new UncheckedIOException( e );
            }
        }
        vault.put( "Projects/assets/diagram.png", PNG.clone() );
        vault.put( "Projects/assets/unused.png", PNG.clone() );
        return vault;
    }
}

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
package com.wikantik.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 *  Crash-safe file replacement: the content goes to a sibling temp file, is fsynced, and is then
 *  moved over the target with {@code ATOMIC_MOVE}.  A reader (or a crash) therefore sees either the
 *  complete old content or the complete new content, never a truncated file.  Every failure is
 *  reported as an {@link IOException} and the temp file is deleted.
 */
public final class AtomicFileWriter {

    private static final Logger LOG = LogManager.getLogger( AtomicFileWriter.class );

    /** Suffix of the in-flight temp file; deliberately not a wiki page or properties extension. */
    static final String TMP_SUFFIX = ".tmp";

    private static final AtomicLong SEQ = new AtomicLong();

    /** Produces the file content on the supplied stream. */
    @FunctionalInterface
    public interface ContentWriter {
        void writeTo( OutputStream out ) throws IOException;
    }

    private AtomicFileWriter() {}

    /**
     *  Atomically replaces {@code target} with {@code content}.
     *
     *  @param target file to create or replace
     *  @param content bytes to write
     *  @throws IOException if the write, fsync or move fails; the previous target content is intact
     */
    public static void write( final Path target, final byte[] content ) throws IOException {
        write( target, out -> out.write( content ) );
    }

    /**
     *  Atomically replaces {@code target} with whatever {@code writer} produces.
     *
     *  @param target file to create or replace
     *  @param writer content producer; closing the stream it is given only flushes it
     *  @throws IOException if the write, fsync or move fails; the previous target content is intact
     */
    public static void write( final Path target, final ContentWriter writer ) throws IOException {
        final Path abs = target.toAbsolutePath();
        final Path tmp = abs.resolveSibling( abs.getFileName() + "." + ProcessHandle.current().pid() + "."
                                             + SEQ.incrementAndGet() + TMP_SUFFIX );
        boolean moved = false;
        try {
            try( FileOutputStream fos = new FileOutputStream( tmp.toFile() );
                 OutputStream out = new BufferedOutputStream( fos ) ) {
                writer.writeTo( new FilterOutputStream( out ) {
                    @Override public void write( final byte[] b, final int off, final int len ) throws IOException {
                        this.out.write( b, off, len );
                    }
                    @Override public void close() throws IOException {
                        flush(); // a writer closing its wrapper must not close the file before the fsync
                    }
                } );
                out.flush();
                fos.getFD().sync();
            }
            try {
                Files.move( tmp, abs, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING );
            } catch( final AtomicMoveNotSupportedException e ) {
                LOG.warn( "Filesystem refused ATOMIC_MOVE for {}; falling back to a non-atomic replace: {}", abs, e.getMessage() );
                Files.move( tmp, abs, StandardCopyOption.REPLACE_EXISTING );
            }
            moved = true;
        } finally {
            if( !moved ) {
                try {
                    Files.deleteIfExists( tmp );
                } catch( final IOException e ) {
                    LOG.warn( "Could not delete temp file {} after a failed write of {}: {}", tmp, abs, e.getMessage() );
                }
            }
        }
    }
}

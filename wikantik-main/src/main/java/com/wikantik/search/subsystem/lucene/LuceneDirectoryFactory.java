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
package com.wikantik.search.subsystem.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;

/**
 * Single chooser for the Lucene {@link Directory} backing the BM25 index.
 *
 * <p>Two valid values for the {@code kind} parameter:</p>
 * <ul>
 *   <li>{@code "nio"} — {@link NIOFSDirectory}. Per-read {@code FileChannel.read()}
 *       + buffer copy. Conservative default; matches all pre-flag deploys.</li>
 *   <li>{@code "mmap"} — {@link MMapDirectory}. Maps index files into virtual
 *       memory; reads become memory accesses served by the OS page cache.
 *       Lucene's recommended default on 64-bit Linux. Failure mode of a
 *       corrupt page is {@code SIGBUS} (process crash) instead of an
 *       {@link IOException}; tradeoff is visibility vs throughput.</li>
 * </ul>
 *
 * <p>Selection is driven by the {@code wikantik.search.lucene.directory.kind}
 * property (default {@code "nio"}). {@link LuceneSearchProvider} reads the
 * property once at engine init and threads a boolean down to
 * {@link DefaultLuceneSearcher} / {@link DefaultLuceneIndexer}; every Lucene
 * I/O call site routes through {@link #open(Path, boolean)} so the choice
 * applies uniformly to read and write paths.</p>
 *
 * <p>See {@code docs/superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md}
 * §"Going after it" for the load-test motivation.</p>
 */
public final class LuceneDirectoryFactory {

    private LuceneDirectoryFactory() {}

    /**
     * Configuration property name. Valid values: {@code "nio"} | {@code "mmap"}.
     * Defaults to {@code "nio"} when unset.
     */
    public static final String PROP_KIND = "wikantik.search.lucene.directory.kind";

    /** Default property value: {@code "nio"} (conservative, matches pre-flag behaviour). */
    public static final String DEFAULT_KIND = "nio";

    /**
     * Parse a property value into the {@code useMMap} boolean threaded through
     * the indexer/searcher. Recognised inputs (case-insensitive):
     * {@code "nio"} → {@code false}, {@code "mmap"} → {@code true}. Null/blank
     * yields {@code false}. Anything else throws.
     */
    public static boolean parseKind( final String raw ) {
        if ( raw == null || raw.isBlank() ) return false;
        final String v = raw.trim().toLowerCase( Locale.ROOT );
        return switch ( v ) {
            case "nio"  -> false;
            case "mmap" -> true;
            default -> throw new IllegalArgumentException(
                PROP_KIND + " must be 'nio' or 'mmap', got: '" + raw + "'" );
        };
    }

    /**
     * Open a {@link Directory} at {@code path} using either MMap ({@code useMMap}
     * true) or NIO ({@code useMMap} false). Caller owns the returned instance
     * and is responsible for closing it (usually via try-with-resources).
     */
    public static Directory open( final Path path, final boolean useMMap ) throws IOException {
        if ( path == null ) throw new IllegalArgumentException( "path must not be null" );
        return useMMap ? new MMapDirectory( path ) : new NIOFSDirectory( path );
    }
}

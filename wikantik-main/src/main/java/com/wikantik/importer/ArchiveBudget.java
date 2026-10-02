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

/**
 * The size, count and ratio budget of one vault read. Every check measures streamed bytes, never header-declared
 * sizes; exceeding a configured limit is an {@link ImportLimitException} naming its key, a zip bomb is a
 * {@link VaultArchiveException}.
 */
final class ArchiveBudget {

    private static final long RATIO_FLOOR_BYTES = 1_048_576L;
    private static final long MAX_RATIO = 100L;

    private final ImportLimits limits;
    private long bufferedNoteBytes;

    ArchiveBudget( final ImportLimits limits ) {
        this.limits = limits;
    }

    /** The zip-bomb guard: more than 1 MiB inflated at over 100:1 from the compressed bytes actually consumed. */
    static boolean exceedsRatio( final long entryBytes, final long rawBytes ) {
        return entryBytes > RATIO_FLOOR_BYTES && entryBytes > MAX_RATIO * Math.max( 1, rawBytes );
    }

    void requireEntryCount( final int entries ) throws ImportLimitException {
        if ( entries > limits.maxEntries() ) {
            throw new ImportLimitException( ImportLimits.PROP_MAX_ENTRIES, limits.maxEntries(), "zip entry count" );
        }
    }

    void requirePageCap( final long notes ) throws ImportLimitException {
        if ( notes > limits.maxPages() ) {
            throw new ImportLimitException( ImportLimits.PROP_MAX_PAGES, limits.maxPages(),
                                            "more than " + limits.maxPages() + " notes" );
        }
    }

    /** True while a note of {@code entryBytes} so far is still within the per-page limit (and so worth buffering). */
    boolean bufferable( final long entryBytes ) {
        return entryBytes <= limits.maxPageBytes();
    }

    /** Checks the running uncompressed total and the current entry's inflation ratio. */
    void checkInflation( final String name, final long entryBytes, final long total, final long rawBytes )
            throws VaultArchiveException, ImportLimitException {
        if ( total > limits.maxUncompressedBytes() ) {
            throw new ImportLimitException( ImportLimits.PROP_MAX_UNCOMPRESSED_BYTES,
                                            limits.maxUncompressedBytes(), "uncompressed vault" );
        }
        if ( exceedsRatio( entryBytes, rawBytes ) ) {
            throw new VaultArchiveException( "zip entry '" + name + "' expands more than 100:1 (possible zip bomb; "
                                              + "very highly compressible files over 1 MiB are rejected)" );
        }
    }

    /** Counts a buffered note against {@code wikantik.import.maxNoteTextBytes}. */
    void retainNote( final long size ) throws ImportLimitException {
        bufferedNoteBytes += size;
        if ( bufferedNoteBytes > limits.maxNoteTextBytes() ) {
            throw new ImportLimitException( ImportLimits.PROP_MAX_NOTE_TEXT_BYTES, limits.maxNoteTextBytes(),
                                            "total note text" );
        }
    }

    long bufferedNoteBytes() {
        return bufferedNoteBytes;
    }
}

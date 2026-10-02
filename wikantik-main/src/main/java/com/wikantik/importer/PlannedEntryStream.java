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

import java.io.FilterInputStream;
import java.io.IOException;
import java.util.zip.ZipInputStream;

/**
 * The current zip entry, refusing to yield more than the planned size or to inflate past the zip-bomb ratio.
 * Closing it does not close the zip stream (the sink may close what it is given).
 */
final class PlannedEntryStream extends FilterInputStream {

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
            if ( ArchiveBudget.exceedsRatio( count, raw.count() - rawStart ) ) {
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

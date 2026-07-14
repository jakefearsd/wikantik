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
package com.wikantik.connectors.gdrive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** In-memory buffer that refuses to grow past a cap (OOM defense for Drive downloads).
 *  Exceeding the cap throws {@link IOException}, which the connector's poll() catch degrades to an
 *  incomplete batch — never an unbounded allocation. Package-private; used by {@link GoogleDriveApi}. */
final class BoundedByteArrayOutputStream extends OutputStream {

    private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
    private final int max;
    private int count;

    BoundedByteArrayOutputStream( final int max ) { this.max = max; }

    @Override public void write( final int b ) throws IOException {
        ensureCapacity( 1 );
        delegate.write( b );
    }

    @Override public void write( final byte[] b, final int off, final int len ) throws IOException {
        ensureCapacity( len );
        delegate.write( b, off, len );
    }

    private void ensureCapacity( final int len ) throws IOException {
        if ( count + len > max ) {
            throw new IOException( "download exceeds max " + max + " bytes — aborted (raise the cap or "
                + "remove the oversized file from the synced folder)" );
        }
        count += len;
    }

    byte[] toByteArray() { return delegate.toByteArray(); }
}

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
package com.wikantik.search.embedding.experiment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Encodes/decodes {@code float[]} vectors as little-endian {@code float32}
 * byte streams for storage in the experiment-sandbox {@code BYTEA} column.
 * Little-endian is chosen to match pgvector's wire format should we ever
 * migrate rows across without re-embedding.
 */
public final class VectorCodec {

    private VectorCodec() {}

    public static byte[] encode( final float[] vec ) {
        final ByteBuffer buf = ByteBuffer.allocate( vec.length * Float.BYTES ).order( ByteOrder.LITTLE_ENDIAN );
        for( final float f : vec ) buf.putFloat( f );
        return buf.array();
    }

    public static float[] decode( final byte[] bytes, final int expectedDim ) {
        if( bytes.length != expectedDim * Float.BYTES ) {
            throw new IllegalArgumentException( "byte length " + bytes.length
                + " does not match dim " + expectedDim + " (expected " + expectedDim * Float.BYTES + ")" );
        }
        final ByteBuffer buf = ByteBuffer.wrap( bytes ).order( ByteOrder.LITTLE_ENDIAN );
        final float[] out = new float[ expectedDim ];
        for( int i = 0; i < expectedDim; i++ ) out[ i ] = buf.getFloat();
        return out;
    }
}

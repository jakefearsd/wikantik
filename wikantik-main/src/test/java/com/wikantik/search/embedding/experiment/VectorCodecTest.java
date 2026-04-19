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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VectorCodecTest {

    @Test
    void roundTripsArbitraryValues() {
        final float[] in = { 0f, 1f, -1f, 3.14159f, -2.71828f, Float.MIN_NORMAL, Float.MAX_VALUE };
        final byte[] encoded = VectorCodec.encode( in );
        assertEquals( in.length * Float.BYTES, encoded.length );
        final float[] out = VectorCodec.decode( encoded, in.length );
        assertArrayEquals( in, out, 0f );
    }

    @Test
    void emptyVectorEncodesToEmptyBytes() {
        final byte[] encoded = VectorCodec.encode( new float[ 0 ] );
        assertEquals( 0, encoded.length );
        assertEquals( 0, VectorCodec.decode( encoded, 0 ).length );
    }

    @Test
    void usesLittleEndianLayout() {
        // 1.0f is 0x3F800000 big-endian → 0x00,0x00,0x80,0x3F little-endian
        final byte[] encoded = VectorCodec.encode( new float[] { 1.0f } );
        assertArrayEquals( new byte[] { 0x00, 0x00, (byte) 0x80, 0x3F }, encoded );
    }

    @Test
    void decodeRejectsDimensionMismatch() {
        final byte[] encoded = VectorCodec.encode( new float[] { 1f, 2f, 3f } );
        assertThrows( IllegalArgumentException.class, () -> VectorCodec.decode( encoded, 4 ) );
        assertThrows( IllegalArgumentException.class, () -> VectorCodec.decode( encoded, 2 ) );
    }
}

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
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ChunkVectorBytesTest {

    private static byte[] encodeLE( final float[] v ) {
        final ByteBuffer bb = ByteBuffer.allocate( v.length * Float.BYTES ).order( ByteOrder.LITTLE_ENDIAN );
        for ( final float f : v ) bb.putFloat( f );
        return bb.array();
    }

    @Test
    void roundTripsLittleEndianFloats() {
        final float[] expected = { 1.0f, -2.5f, 3.25f, 0.0f };
        final float[] out = ChunkVectorBytes.decode( UUID.randomUUID(), encodeLE( expected ), 4 );
        assertArrayEquals( expected, out, 0.0f );
    }

    @Test
    void returnsNullForNullBytes() {
        assertNull( ChunkVectorBytes.decode( UUID.randomUUID(), null, 4 ) );
    }

    @Test
    void throwsOnByteLengthMismatch() {
        final byte[] tooShort = new byte[ 3 * Float.BYTES ];
        assertThrows( IllegalStateException.class,
            () -> ChunkVectorBytes.decode( UUID.randomUUID(), tooShort, 4 ) );
    }
}

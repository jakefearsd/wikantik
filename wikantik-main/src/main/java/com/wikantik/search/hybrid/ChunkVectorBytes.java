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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.UUID;

/**
 * Decodes a chunk embedding stored as a little-endian float32 {@code bytea}
 * ({@code content_chunk_embeddings.vec}) into a {@code float[]}. Shared by every
 * {@link ChunkVectorIndex} backend so the on-disk codec lives in exactly one place.
 */
public final class ChunkVectorBytes {

    private static final Logger LOG = LogManager.getLogger( ChunkVectorBytes.class );

    private ChunkVectorBytes() {}

    /**
     * @param id  chunk id, for log context only
     * @param raw little-endian float32 bytes ({@code dim * 4} long), or {@code null}
     * @param dim declared vector dimension
     * @return decoded vector, or {@code null} if {@code raw} was {@code null}
     * @throws IllegalStateException if {@code raw.length != dim * 4}
     */
    public static float[] decode( final UUID id, final byte[] raw, final int dim ) {
        if ( raw == null ) {
            LOG.warn( "ChunkVectorBytes: chunk {} has null vec, skipping", id );
            return null;
        }
        if ( raw.length != dim * Float.BYTES ) {
            LOG.warn( "ChunkVectorBytes: chunk {} vec bytes={} expected {} (dim={})",
                id, raw.length, dim * Float.BYTES, dim );
            throw new IllegalStateException( "Corrupt vector bytes for chunk " + id
                + ": got " + raw.length + " bytes, expected " + ( dim * Float.BYTES ) );
        }
        final float[] out = new float[ dim ];
        final FloatBuffer fb = ByteBuffer.wrap( raw ).order( ByteOrder.LITTLE_ENDIAN ).asFloatBuffer();
        fb.get( out );
        return out;
    }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link PgVectorChunkVectorIndex#formatVector(float[])} —
 * the pgvector string-literal codec used when issuing HNSW proximity queries.
 * No database required; all three tests exercise pure computation.
 */
class PgVectorChunkVectorIndexTest {

    @Test
    void formatVector_emitsPgvectorLiteral() {
        final float[] v = { 0.1f, -0.2f, 0.3f };
        assertEquals( "[0.1,-0.2,0.3]", PgVectorChunkVectorIndex.formatVector( v ) );
    }

    @Test
    void formatVector_emptyVector_emitsEmptyLiteral() {
        assertEquals( "[]", PgVectorChunkVectorIndex.formatVector( new float[ 0 ] ) );
    }

    @Test
    void formatVector_nullVector_throws() {
        assertThrows( IllegalArgumentException.class,
            () -> PgVectorChunkVectorIndex.formatVector( null ) );
    }
}

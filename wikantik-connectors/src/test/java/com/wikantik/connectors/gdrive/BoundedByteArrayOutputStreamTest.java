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

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/** OOM defense for Drive downloads: {@link BoundedByteArrayOutputStream} must refuse to buffer
 *  past its cap by throwing IOException (which the connector's poll() catch degrades to an
 *  incomplete batch), never accumulate unboundedly. */
class BoundedByteArrayOutputStreamTest {

    @Test void underCapAccumulates() throws IOException {
        final BoundedByteArrayOutputStream out = new BoundedByteArrayOutputStream( 10 );
        out.write( new byte[]{ 1, 2, 3 } );
        out.write( 4 );
        assertArrayEquals( new byte[]{ 1, 2, 3, 4 }, out.toByteArray() );
    }

    @Test void exactlyAtCapIsAllowed() throws IOException {
        final BoundedByteArrayOutputStream out = new BoundedByteArrayOutputStream( 4 );
        out.write( new byte[4] );
        assertEquals( 4, out.toByteArray().length );
    }

    @Test void overCapThrowsIOException() throws IOException {
        final BoundedByteArrayOutputStream out = new BoundedByteArrayOutputStream( 4 );
        out.write( new byte[4] );
        assertThrows( IOException.class, () -> out.write( 5 ) );
        assertThrows( IOException.class, () -> out.write( new byte[]{ 6, 7 }, 0, 2 ) );
    }
}

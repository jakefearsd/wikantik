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
package com.wikantik.collab.model;

/**
 * A meta field assignment as a pull carries it (section 6.1, A14): the server-resolved value ({@code value} null removes
 * the field) and {@code by}, the public display name of the participant who authored it, or null for a server op (a
 * publish fold or an external merge) or an author with no public name. Unlike {@link StoredMetaOp} it holds no author
 * key, so no login ever reaches the wire.
 */
public record MetaOp( String field, Object value, String by ) {
    public MetaOp {
        ModelChecks.nonBlank( field, "field" );
    }

    /** A server op: no author. */
    public MetaOp( final String field, final Object value ) {
        this( field, value, null );
    }
}

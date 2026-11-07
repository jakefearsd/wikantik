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

import java.util.Objects;

/**
 * One meta edit of a push (A14): a field SET (a null {@code value} removes the field), or an ADD or REMOVE of one item
 * on a set field ({@code tags}, {@code related}, {@code aliases}). ADD and REMOVE take a non-blank string. The field
 * name is validated by the session ({@code MetaEdits.invalid}), not here, so a bad name is a 400 {@code rejected}
 * answer rather than a construction failure.
 */
public record MetaEdit( String field, Kind kind, Object value ) {

    /** SET assigns the value; ADD and REMOVE change one item of a set field. */
    public enum Kind { SET, ADD, REMOVE }

    public MetaEdit {
        Objects.requireNonNull( kind, "kind" );
        if( kind != Kind.SET && !( value instanceof String s && !s.isBlank() ) ) {
            throw new IllegalArgumentException( kind + " takes a non-blank string, got " + value );
        }
    }

    public static MetaEdit set( final String field, final Object value ) {
        return new MetaEdit( field, Kind.SET, value );
    }

    public static MetaEdit add( final String field, final String item ) {
        return new MetaEdit( field, Kind.ADD, item );
    }

    public static MetaEdit remove( final String field, final String item ) {
        return new MetaEdit( field, Kind.REMOVE, item );
    }
}

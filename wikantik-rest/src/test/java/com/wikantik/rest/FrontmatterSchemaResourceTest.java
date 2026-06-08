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
package com.wikantik.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FrontmatterSchemaResourceTest {

    @SuppressWarnings( "unchecked" )
    @Test
    void payloadHasTypeFieldFromDefaultSchema() {
        final Map< String, Object > payload = FrontmatterSchemaResource.schemaPayload();
        final List< Map< String, Object > > fields =
                ( List< Map< String, Object > > ) payload.get( "fields" );
        assertFalse( fields.isEmpty() );

        final Map< String, Object > type = fields.stream()
                .filter( m -> "type".equals( m.get( "key" ) ) ).findFirst().orElseThrow();
        assertEquals( "ENUM", type.get( "widget" ) );
        assertEquals( Boolean.TRUE, type.get( "open" ) );
        assertTrue( ( ( List< ? > ) type.get( "canonicalValues" ) ).contains( "article" ) );
    }

    @SuppressWarnings( "unchecked" )
    @Test
    void canonicalIdIsFirstAndReadonly() {
        final Map< String, Object > payload = FrontmatterSchemaResource.schemaPayload();
        final List< Map< String, Object > > fields =
                ( List< Map< String, Object > > ) payload.get( "fields" );
        assertEquals( "canonical_id", fields.get( 0 ).get( "key" ) );
        assertEquals( "READONLY", fields.get( 0 ).get( "widget" ) );
    }
}

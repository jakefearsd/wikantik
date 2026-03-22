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
package com.wikantik.mcp.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataOperationsTest {

    private Map< String, Object > metadata() {
        return new LinkedHashMap<>();
    }

    // --- set ---

    @Test
    void testSetAddsField() {
        final Map< String, Object > meta = metadata();
        assertNull( MetadataOperations.apply( meta, "type", "set", "article" ) );
        assertEquals( "article", meta.get( "type" ) );
    }

    @Test
    void testSetOverwritesExisting() {
        final Map< String, Object > meta = metadata();
        meta.put( "status", "draft" );
        assertNull( MetadataOperations.apply( meta, "status", "set", "active" ) );
        assertEquals( "active", meta.get( "status" ) );
    }

    @Test
    void testSetNullValue() {
        final Map< String, Object > meta = metadata();
        assertNull( MetadataOperations.apply( meta, "field", "set", null ) );
        assertTrue( meta.containsKey( "field" ) );
        assertNull( meta.get( "field" ) );
    }

    // --- delete ---

    @Test
    void testDeleteRemovesField() {
        final Map< String, Object > meta = metadata();
        meta.put( "status", "draft" );
        assertNull( MetadataOperations.apply( meta, "status", "delete", null ) );
        assertFalse( meta.containsKey( "status" ) );
    }

    @Test
    void testDeleteNonexistentFieldIsNoOp() {
        final Map< String, Object > meta = metadata();
        assertNull( MetadataOperations.apply( meta, "missing", "delete", null ) );
    }

    // --- append_to_list ---

    @Test
    void testAppendCreatesNewList() {
        final Map< String, Object > meta = metadata();
        assertNull( MetadataOperations.apply( meta, "tags", "append_to_list", "ai" ) );
        assertEquals( List.of( "ai" ), meta.get( "tags" ) );
    }

    @Test
    void testAppendAddsToExistingList() {
        final Map< String, Object > meta = metadata();
        meta.put( "tags", new ArrayList<>( List.of( "ai" ) ) );
        assertNull( MetadataOperations.apply( meta, "tags", "append_to_list", "ml" ) );
        assertEquals( List.of( "ai", "ml" ), meta.get( "tags" ) );
    }

    @Test
    void testAppendIsIdempotent() {
        final Map< String, Object > meta = metadata();
        meta.put( "tags", new ArrayList<>( List.of( "ai" ) ) );
        assertNull( MetadataOperations.apply( meta, "tags", "append_to_list", "ai" ) );
        assertEquals( List.of( "ai" ), meta.get( "tags" ) );
    }

    @Test
    void testAppendToNonListFieldReturnsError() {
        final Map< String, Object > meta = metadata();
        meta.put( "type", "article" );
        final String error = MetadataOperations.apply( meta, "type", "append_to_list", "value" );
        assertNotNull( error );
        assertTrue( error.contains( "not a list" ) );
    }

    // --- remove_from_list ---

    @Test
    void testRemoveFromList() {
        final Map< String, Object > meta = metadata();
        meta.put( "tags", new ArrayList<>( List.of( "ai", "ml" ) ) );
        assertNull( MetadataOperations.apply( meta, "tags", "remove_from_list", "ai" ) );
        assertEquals( List.of( "ml" ), meta.get( "tags" ) );
    }

    @Test
    void testRemoveNonexistentValueIsNoOp() {
        final Map< String, Object > meta = metadata();
        meta.put( "tags", new ArrayList<>( List.of( "ai" ) ) );
        assertNull( MetadataOperations.apply( meta, "tags", "remove_from_list", "missing" ) );
        assertEquals( List.of( "ai" ), meta.get( "tags" ) );
    }

    @Test
    void testRemoveFromNullFieldIsNoOp() {
        final Map< String, Object > meta = metadata();
        assertNull( MetadataOperations.apply( meta, "tags", "remove_from_list", "ai" ) );
    }

    @Test
    void testRemoveFromNonListFieldReturnsError() {
        final Map< String, Object > meta = metadata();
        meta.put( "type", "article" );
        final String error = MetadataOperations.apply( meta, "type", "remove_from_list", "value" );
        assertNotNull( error );
        assertTrue( error.contains( "not a list" ) );
    }

    // --- error cases ---

    @Test
    void testNullFieldReturnsError() {
        final String error = MetadataOperations.apply( metadata(), null, "set", "value" );
        assertNotNull( error );
        assertTrue( error.contains( "missing" ) );
    }

    @Test
    void testNullActionReturnsError() {
        final String error = MetadataOperations.apply( metadata(), "field", null, "value" );
        assertNotNull( error );
        assertTrue( error.contains( "missing" ) );
    }

    @Test
    void testUnknownActionReturnsError() {
        final String error = MetadataOperations.apply( metadata(), "field", "rename", "value" );
        assertNotNull( error );
        assertTrue( error.contains( "Unknown action" ) );
        assertTrue( error.contains( "rename" ) );
    }
}

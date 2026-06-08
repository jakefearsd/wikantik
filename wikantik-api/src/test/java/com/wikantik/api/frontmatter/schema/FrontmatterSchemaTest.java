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
package com.wikantik.api.frontmatter.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrontmatterSchemaTest {

    @Test
    void typeFieldIsOpenEnumWithCanonicalsAndSuggestions() {
        final FieldSpec type = FrontmatterSchema.defaultSchema().field( "type" ).orElseThrow();
        assertEquals( Widget.ENUM, type.widget() );
        assertTrue( type.open(), "type is a curated-open enum" );
        assertTrue( type.canonicalValues().contains( "article" ) );
        assertTrue( type.canonicalValues().contains( "hub" ) );
        assertEquals( "article", type.suggestionMap().get( "report" ) );
    }

    @Test
    void statusFieldIsOpenEnumWithSuggestions() {
        final FieldSpec status = FrontmatterSchema.defaultSchema().field( "status" ).orElseThrow();
        assertEquals( Widget.ENUM, status.widget() );
        assertTrue( status.open() );
        assertTrue( status.canonicalValues().contains( "active" ) );
        assertEquals( "active", status.suggestionMap().get( "published" ) );
    }

    @Test
    void audienceIsClosedEnum() {
        final FieldSpec audience = FrontmatterSchema.defaultSchema().field( "audience" ).orElseThrow();
        assertEquals( Widget.ENUM, audience.widget() );
        assertFalse( audience.open(), "audience is a closed enum" );
        assertTrue( audience.canonicalValues().contains( "both" ) );
    }

    @Test
    void summaryHasSeoLengthBounds() {
        final FieldSpec summary = FrontmatterSchema.defaultSchema().field( "summary" ).orElseThrow();
        assertEquals( 50, summary.minLen().intValue() );
        assertEquals( 160, summary.maxLen().intValue() );
    }

    @Test
    void clusterCarriesSlugPattern() {
        assertNotNull( FrontmatterSchema.defaultSchema().field( "cluster" ).orElseThrow().pattern() );
    }

    @Test
    void canonicalIdIsReadonlyAndFirst() {
        final FrontmatterSchema schema = FrontmatterSchema.defaultSchema();
        assertEquals( Widget.READONLY, schema.field( "canonical_id" ).orElseThrow().widget() );
        assertEquals( "canonical_id", schema.fields().get( 0 ).key() );
    }

    @Test
    void runbookFieldUsesRunbookBlockWidget() {
        assertEquals( Widget.RUNBOOK_BLOCK,
                FrontmatterSchema.defaultSchema().field( "runbook" ).orElseThrow().widget() );
    }
}

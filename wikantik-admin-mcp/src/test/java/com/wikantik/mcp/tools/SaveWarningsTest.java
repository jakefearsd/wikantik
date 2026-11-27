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

import com.wikantik.api.content.ContentViolation;
import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;
import com.wikantik.api.frontmatter.schema.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The clear-before / drain-after sequence {@code update_page} and {@code write_pages} share. */
class SaveWarningsTest {

    private static final FieldViolation FM = FieldViolation.of( "status", Severity.WARNING, "status.x", "fm warning" );
    private static final ContentViolation MATH =
        new ContentViolation( "body", Severity.WARNING, "MATH", "math warning", null );

    @AfterEach
    void clean() {
        SaveWarnings.clear();
    }

    @Test
    void clearEmptiesBothSinks() {
        FrontmatterWarningSink.put( "P", List.of( FM ) );
        ContentWarningSink.put( List.of( MATH ) );

        SaveWarnings.clear();

        assertTrue( FrontmatterWarningSink.drain( "P" ).isEmpty(), "frontmatter sink" );
        assertTrue( ContentWarningSink.drain().isEmpty(), "content sink" );
    }

    @Test
    void attachAddsBothWarningKindsWhenPresentAndDrainsTheSinks() {
        FrontmatterWarningSink.put( "P", List.of( FM ) );
        ContentWarningSink.put( List.of( MATH ) );
        final Map< String, Object > entry = new LinkedHashMap<>();

        SaveWarnings.attach( entry, "P" );

        assertEquals( List.of( FM ), entry.get( "frontmatterWarnings" ) );
        assertEquals( List.of( MATH ), entry.get( "mathWarnings" ) );
        assertTrue( FrontmatterWarningSink.drain( "P" ).isEmpty() );
        assertTrue( ContentWarningSink.drain().isEmpty() );
    }

    @Test
    void attachAddsNothingWhenThereAreNoWarnings() {
        final Map< String, Object > entry = new LinkedHashMap<>();

        SaveWarnings.attach( entry, "P" );

        assertTrue( entry.isEmpty(), entry.toString() );
    }

    @Test
    void onlyMathWarningsAddOnlyTheMathKey() {
        ContentWarningSink.put( List.of( MATH ) );
        final Map< String, Object > entry = new LinkedHashMap<>();

        SaveWarnings.attach( entry, "P" );

        assertFalse( entry.containsKey( "frontmatterWarnings" ) );
        assertEquals( List.of( MATH ), entry.get( "mathWarnings" ) );
    }

    @Test
    void toolComputedWarningsAreListedAfterTheSinksOwn() {
        final FieldViolation extra = FieldViolation.of( "body", Severity.WARNING, "extra", "extra warning" );
        FrontmatterWarningSink.put( "P", List.of( FM ) );
        final Map< String, Object > entry = new LinkedHashMap<>();

        SaveWarnings.attach( entry, "P", List.of( extra ) );

        assertEquals( List.of( FM, extra ), entry.get( "frontmatterWarnings" ) );
    }

    @Test
    void extraWarningsAloneStillProduceTheKey() {
        final FieldViolation extra = FieldViolation.of( "body", Severity.WARNING, "extra", "extra warning" );
        final Map< String, Object > entry = new LinkedHashMap<>();

        SaveWarnings.attach( entry, "P", List.of( extra ) );

        assertEquals( List.of( extra ), entry.get( "frontmatterWarnings" ) );
    }
}

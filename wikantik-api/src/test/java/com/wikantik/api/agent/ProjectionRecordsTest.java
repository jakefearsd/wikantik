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
package com.wikantik.api.agent;

import com.wikantik.api.pagegraph.StructuralConflict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionRecordsTest {

    private static ForAgentProjection projection( final String id, final String slug, final List< String > missing ) {
        return new ForAgentProjection( id, slug, "T", "article", null, null, null, null, null, null, "s",
                null, null, null, null, null, null, false, "/f", "/r", false, missing, null );
    }

    @Test
    void projectionRequiresIdAndSlugAndDefaultsLists() {
        assertThrows( IllegalArgumentException.class, () -> projection( " ", "s", null ) );
        assertThrows( IllegalArgumentException.class, () -> projection( "id", null, null ) );
        final List< String > src = new ArrayList<>( List.of( "summary" ) );
        final ForAgentProjection p = projection( "id", "slug", src );
        src.add( "later" );
        assertEquals( List.of( "summary" ), p.missingFields() );
        assertEquals( List.of(), p.keyFacts() );
        assertEquals( List.of(), p.headingsOutline() );
        assertEquals( List.of(), p.recentChanges() );
        assertEquals( List.of(), p.mcpToolHints() );
        assertEquals( List.of(), p.staleCitations() );
    }

    @Test
    void runbookBlockNullListsBecomeEmpty() {
        final RunbookBlock b = new RunbookBlock( null, null, List.of( "do it" ), null, null, null );
        assertEquals( List.of( "do it" ), b.steps() );
        assertEquals( List.of(), b.when_to_use() );
        assertEquals( List.of(), b.inputs() );
        assertEquals( List.of(), b.pitfalls() );
        assertEquals( List.of(), b.related_tools() );
        assertEquals( List.of(), b.references() );
    }

    @Test
    void structuralConflictValidates() {
        assertThrows( IllegalArgumentException.class,
                () -> new StructuralConflict( "", "c", StructuralConflict.Kind.HEADLESS_CLUSTER, "d" ) );
        assertThrows( IllegalArgumentException.class, () -> new StructuralConflict( "s", "c", null, "d" ) );
        assertEquals( "", new StructuralConflict( "s", null, StructuralConflict.Kind.CLUSTERLESS_HUB, null ).detail() );
    }
}

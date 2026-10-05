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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PageTemplatesTest {

    @Test
    void offersOneTemplatePerTypeWithTitlePlaceholder() {
        final List< PageTemplates.Template > all = PageTemplates.all();
        assertEquals( List.of( "article", "reference", "design", "runbook", "hub" ),
                all.stream().map( PageTemplates.Template::type ).toList() );
        for ( final PageTemplates.Template t : all ) {
            assertTrue( t.body().startsWith( "# {{title}}" ), t.type() );
            assertEquals( t.type(), t.metadata().get( "type" ) );
            assertEquals( "active", t.metadata().get( "status" ) );
        }
    }

    @Test
    void runbookTemplateCarriesRunbookBlock() {
        final Map< String, Object > meta = PageTemplates.forType( "runbook" ).orElseThrow().metadata();
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > block = ( Map< String, Object > ) meta.get( "runbook" );
        assertEquals( List.of( "First step.", "Second step." ), block.get( "steps" ) );
        assertTrue( block.containsKey( "when_to_use" ) );
        assertTrue( block.containsKey( "pitfalls" ) );
    }

    @Test
    void unknownTypeIsEmptyAndMetadataIsImmutable() {
        assertTrue( PageTemplates.forType( "nonsense" ).isEmpty() );
        final Map< String, Object > meta = PageTemplates.forType( "hub" ).orElseThrow().metadata();
        assertThrows( UnsupportedOperationException.class, () -> meta.put( "x", 1 ) );
    }
}

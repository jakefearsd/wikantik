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
package com.wikantik.frontmatter.schema;

import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.PageTemplates;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PageTemplatesValidityTest {

    private final SchemaDrivenFrontmatterValidator validator =
            new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() );

    @Test
    void everySchemaTypeHasExactlyOneTemplate() {
        final Set< String > schemaTypes = Set.copyOf(
                FrontmatterSchema.defaultSchema().field( "type" ).orElseThrow().canonicalValues() );
        final List< String > templateTypes = PageTemplates.all().stream().map( PageTemplates.Template::type ).toList();
        assertEquals( schemaTypes, Set.copyOf( templateTypes ) );
        assertEquals( templateTypes.size(), Set.copyOf( templateTypes ).size(), "no duplicate templates" );
    }

    @Test
    void everyTemplateValidatesWithNoErrorsAndNoWarnings() {
        for ( final PageTemplates.Template t : PageTemplates.all() ) {
            final Map< String, Object > m = new HashMap<>( t.metadata() );
            m.put( "date", "2026-09-30" );                    // what the client adds
            if ( "hub".equals( t.type() ) ) {
                m.put( "cluster", "example-cluster" );         // the modal requires one for hubs
            }
            final List< FieldViolation > vs = validator.validate( m, ValidationCtx.lenient() );
            assertTrue( vs.isEmpty(), t.type() + " → " + vs.stream().map( FieldViolation::code )
                    .collect( Collectors.joining( ", " ) ) );
        }
    }

    @Test
    void bodiesStartWithTheTitleHeadingAndUseOnlyKnownPlaceholders() {
        for ( final PageTemplates.Template t : PageTemplates.all() ) {
            assertTrue( t.body().startsWith( "# {{title}}\n" ), t.type() );
            assertFalse( t.body().replace( "{{title}}", "" ).contains( "{{" ), t.type() );
            assertFalse( t.metadata().containsKey( "date" ) || t.metadata().containsKey( "cluster" ), t.type() );
        }
    }
}

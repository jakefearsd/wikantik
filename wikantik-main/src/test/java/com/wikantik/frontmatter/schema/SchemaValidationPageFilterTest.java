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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchemaValidationPageFilterTest {

    private final SchemaValidationPageFilter filter = new SchemaValidationPageFilter(
            new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() ),
            ValidationCtx.lenient(), true );

    @BeforeEach
    void clearSink() {
        FrontmatterWarningSink.clear();
    }

    @Test
    void malformedYamlThrowsWithYamlViolation() {
        final FrontmatterValidationException ex = assertThrows( FrontmatterValidationException.class,
                () -> filter.preSave( null, "---\ntags: [a, b\n---\n# body\n" ) );
        assertEquals( "__yaml__", ex.violations().get( 0 ).field() );
    }

    @Test
    void badAudienceThrowsValidationException() {
        final FrontmatterValidationException ex = assertThrows( FrontmatterValidationException.class,
                () -> filter.preSave( null, "---\ntype: article\naudience: robots\n---\n# body\n" ) );
        assertTrue( ex.violations().stream().anyMatch( v -> v.field().equals( "audience" ) ) );
    }

    @Test
    void nonCanonicalStatusDoesNotThrowAndStashesWarning() throws Exception {
        final String content = "---\nstatus: published\n---\n# body\n";
        assertSame( content, filter.preSave( null, content ) );
        final List< FieldViolation > warnings = FrontmatterWarningSink.drain( null );
        assertTrue( warnings.stream().anyMatch( v -> v.field().equals( "status" ) ),
                "non-canonical status should be stashed as a warning" );
    }

    @Test
    void cleanPagePassesThroughWithNoWarnings() throws Exception {
        final String content = "---\ntype: article\nstatus: active\n---\n# body\n";
        assertSame( content, filter.preSave( null, content ) );
        assertTrue( FrontmatterWarningSink.drain( null ).isEmpty() );
    }

    @Test
    void contentWithoutFrontmatterPassesThrough() throws Exception {
        final String content = "# just a body, no frontmatter\n";
        assertSame( content, filter.preSave( null, content ) );
    }

    @Test
    void disabledFilterIsNoOp() throws Exception {
        final SchemaValidationPageFilter disabled = new SchemaValidationPageFilter(
                new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() ),
                ValidationCtx.lenient(), false );
        final String content = "---\naudience: robots\n---\n# body\n";
        assertSame( content, disabled.preSave( null, content ) );
    }
}

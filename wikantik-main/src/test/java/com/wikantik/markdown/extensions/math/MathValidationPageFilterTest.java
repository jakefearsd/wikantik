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
package com.wikantik.markdown.extensions.math;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.content.ContentViolation;
import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.frontmatter.schema.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathValidationPageFilterTest {

    private final MathValidationPageFilter filter = new MathValidationPageFilter( true );

    @BeforeEach
    void clearSink() {
        ContentWarningSink.clear();
    }

    // -------------------------------------------------------------------------
    // Clean page — no throw, no warnings
    // -------------------------------------------------------------------------

    @Test
    void cleanPageReturnsContentUnchanged() throws Exception {
        final String body = "# Hello\n\nSome text without math.\n";
        final String result = filter.preSave( null, body );
        assertEquals( body, result );
        assertTrue( ContentWarningSink.drain().isEmpty() );
    }

    @Test
    void cleanPageWithValidDisplayBlock() throws Exception {
        final String content = "---\ntitle: Test\n---\n# Math\n\n$$\n\\frac{1}{2}\n$$\n";
        final String result = filter.preSave( null, content );
        assertEquals( content, result );
        assertTrue( ContentWarningSink.drain().isEmpty() );
    }

    // -------------------------------------------------------------------------
    // Error: notIsolated — throws ContentValidationException
    // -------------------------------------------------------------------------

    @Test
    void inlineGluedDisplayBlockThrowsWithNotIsolatedCode() {
        // FastenerEngineering-style: $$ delimiters glued to text, with a \command inside
        final String body = "Text $$\\frac{1}{2}$$ more text.\n";
        final ContentValidationException ex = assertThrows(
                ContentValidationException.class,
                () -> filter.preSave( null, body ) );

        final List< ContentViolation > violations = ex.violations();
        assertFalse( violations.isEmpty(), "Expected at least one violation" );
        final ContentViolation v = violations.get( 0 );
        assertEquals( "math", v.locus() );
        assertEquals( Severity.ERROR, v.severity() );
        assertEquals( "math.display.notIsolated", v.code() );
        assertNotNull( v.location() );
        assertNotNull( v.location().excerpt() );
        assertNotNull( v.location().caret() );
        // The excerpt should contain the offending text
        assertTrue( v.location().excerpt().contains( "$$" ),
                "Excerpt should contain $$ but was: " + v.location().excerpt() );
        // Caret must have at least one ^
        assertTrue( v.location().caret().contains( "^" ),
                "Caret must contain ^ but was: " + v.location().caret() );
    }

    // -------------------------------------------------------------------------
    // Error via frontmatter: strip FM first, location relative to body
    // -------------------------------------------------------------------------

    @Test
    void bodyRelativeOffsetAfterFrontmatter() {
        // Page with frontmatter: the offending $$ is in the body, NOT at offset 0 of full content.
        // "---\ntitle: Widgets\n---\n" is the frontmatter block (23 chars).
        // The body is "Text $$\\frac{a}{b}$$ rest.\n"
        final String frontmatter = "---\ntitle: Widgets\n---\n";
        final String bodyPart = "Text $$\\frac{a}{b}$$ rest.\n";
        final String fullContent = frontmatter + bodyPart;

        final ContentValidationException ex = assertThrows(
                ContentValidationException.class,
                () -> filter.preSave( null, fullContent ) );

        final ContentViolation v = ex.violations().get( 0 );
        // The startOffset should be the offset within the body, NOT within the full content.
        // The $$ in the body starts at index 5 ("Text $$...").
        assertEquals( 5, v.location().startOffset(),
                "startOffset must be body-relative (expected 5, the index of $$ in the body)" );
        // Line 1 in the body (body starts fresh at line 1)
        assertEquals( 1, v.location().line(), "line must be 1 (first line of body)" );
        // The excerpt should contain the offending text from the body line
        assertTrue( v.location().excerpt().contains( "$$" ) );
    }

    // -------------------------------------------------------------------------
    // Warning only: no throw, warning in sink
    // -------------------------------------------------------------------------

    @Test
    void warningSqrtUnclosedInIsolatedDisplayBlock() throws Exception {
        // Properly isolated display block with \sqrt[ (unclosed optional arg) — WARNING only
        final String content = "# Title\n\n$$\n\\sqrt[3x\n$$\n";
        assertDoesNotThrow( () -> filter.preSave( null, content ) );
        final List< ContentViolation > warnings = ContentWarningSink.drain();
        assertFalse( warnings.isEmpty(), "Expected sqrtBadOptional warning" );
        final ContentViolation w = warnings.get( 0 );
        assertEquals( Severity.WARNING, w.severity() );
        assertEquals( "math", w.locus() );
        assertTrue( w.code().startsWith( "math." ) );
    }

    // -------------------------------------------------------------------------
    // Disabled: no-op
    // -------------------------------------------------------------------------

    @Test
    void disabledFilterIsNoOp() throws Exception {
        final MathValidationPageFilter disabled = new MathValidationPageFilter( false );
        final String content = "Text $$\\frac{a}{b}$$ rest.\n";
        final String result = disabled.preSave( null, content );
        assertEquals( content, result );
        assertTrue( ContentWarningSink.drain().isEmpty() );
    }

    // -------------------------------------------------------------------------
    // Null/empty content: no-op
    // -------------------------------------------------------------------------

    @Test
    void nullContentIsNoOp() throws Exception {
        final String result = filter.preSave( null, null );
        assertEquals( null, result );
    }

    @Test
    void emptyContentIsNoOp() throws Exception {
        final String result = filter.preSave( null, "" );
        assertEquals( "", result );
    }

    // -------------------------------------------------------------------------
    // Serialization sanity: ContentViolation + Location survive Jackson round-trip
    // -------------------------------------------------------------------------

    @Test
    void contentViolationSurvivesJacksonSerialization() throws Exception {
        final String body = "Text $$\\frac{a}{b}$$ rest.\n";
        ContentValidationException ex = assertThrows(
                ContentValidationException.class,
                () -> filter.preSave( null, body ) );

        final ObjectMapper mapper = new ObjectMapper();
        final String json = mapper.writeValueAsString( ex.violations() );
        assertNotNull( json );
        assertTrue( json.contains( "\"locus\"" ), "JSON must contain locus field" );
        assertTrue( json.contains( "\"severity\"" ), "JSON must contain severity field" );
        assertTrue( json.contains( "\"code\"" ), "JSON must contain code field" );
        assertTrue( json.contains( "\"message\"" ), "JSON must contain message field" );
        assertTrue( json.contains( "\"location\"" ), "JSON must contain location field" );
        assertTrue( json.contains( "\"excerpt\"" ), "JSON must contain excerpt field" );
        assertTrue( json.contains( "\"caret\"" ), "JSON must contain caret field" );
        assertTrue( json.contains( "\"startOffset\"" ), "JSON must contain startOffset field" );
        assertTrue( json.contains( "\"line\"" ), "JSON must contain line field" );
    }
}

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.Severity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SchemaDrivenFrontmatterValidatorTest {

    private final SchemaDrivenFrontmatterValidator validator =
            new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() );

    private Optional< FieldViolation > first( final List< FieldViolation > vs, final String field ) {
        return vs.stream().filter( v -> v.field().equals( field ) ).findFirst();
    }

    @Test
    void closedEnumRejectsUnknownAudience() {
        final List< FieldViolation > vs = validator.validate(
                Map.of( "type", "article", "audience", "robots" ), ValidationCtx.lenient() );
        final FieldViolation v = first( vs, "audience" ).orElseThrow();
        assertEquals( Severity.ERROR, v.severity() );
        assertEquals( "audience.enum.invalid", v.code() );
    }

    @Test
    void openEnumWarnsAndSuggestsForNonCanonicalStatus() {
        final List< FieldViolation > vs = validator.validate(
                Map.of( "status", "published" ), ValidationCtx.lenient() );
        final FieldViolation v = first( vs, "status" ).orElseThrow();
        assertEquals( Severity.WARNING, v.severity() );
        assertEquals( "status.noncanonical", v.code() );
        assertEquals( "active", v.suggestion() );
    }

    @Test
    void openEnumEscalatesToErrorWhenCtxSaysSo() {
        final ValidationCtx strict = new ValidationCtx( p -> true, a -> true, Severity.ERROR );
        final List< FieldViolation > vs = validator.validate(
                Map.of( "status", "published" ), strict );
        assertEquals( Severity.ERROR, first( vs, "status" ).orElseThrow().severity() );
    }

    @Test
    void summaryTooShortWarns() {
        final List< FieldViolation > vs = validator.validate(
                Map.of( "summary", "short" ), ValidationCtx.lenient() );
        assertEquals( Severity.WARNING, first( vs, "summary" ).orElseThrow().severity() );
    }

    @Test
    void malformedClusterSlugErrorsWithSuggestion() {
        final List< FieldViolation > vs = validator.validate(
                Map.of( "cluster", "Interval Trees" ), ValidationCtx.lenient() );
        final FieldViolation v = first( vs, "cluster" ).orElseThrow();
        assertEquals( Severity.ERROR, v.severity() );
        assertEquals( "interval-trees", v.suggestion() );
    }

    @Test
    void badDateErrors() {
        final List< FieldViolation > vs = validator.validate(
                Map.of( "date", "not-a-date" ), ValidationCtx.lenient() );
        assertEquals( Severity.ERROR, first( vs, "date" ).orElseThrow().severity() );
    }

    @Test
    void validInstantVerifiedAtIsAccepted() {
        final List< FieldViolation > vs = validator.validate(
                Map.of( "verified_at", "2026-05-02T00:00:00Z" ), ValidationCtx.lenient() );
        assertTrue( first( vs, "verified_at" ).isEmpty(), "ISO instant must validate" );
    }

    @Test
    void unresolvedRelatedWarns() {
        final ValidationCtx noPages = new ValidationCtx( p -> false, a -> true, Severity.WARNING );
        final List< FieldViolation > vs = validator.validate(
                Map.of( "related", List.of( "NoSuchPage" ) ), noPages );
        assertEquals( Severity.WARNING, first( vs, "related" ).orElseThrow().severity() );
    }

    @Test
    void untrustedVerifiedByWarns() {
        final ValidationCtx untrusted = new ValidationCtx( p -> true, a -> false, Severity.WARNING );
        final List< FieldViolation > vs = validator.validate(
                Map.of( "verified_by", "stranger" ), untrusted );
        assertEquals( Severity.WARNING, first( vs, "verified_by" ).orElseThrow().severity() );
    }

    @Test
    void runbookMissingStepsMapsToRunbookStepsViolation() {
        final Map< String, Object > meta = Map.of(
                "type", "runbook",
                "runbook", Map.of(
                        "when_to_use", List.of( "when X happens" ),
                        "steps", List.of( "only one step" ),
                        "pitfalls", List.of( "(none known)" ) ) );
        final List< FieldViolation > vs = validator.validate( meta, ValidationCtx.lenient() );
        final FieldViolation v = first( vs, "runbook.steps" ).orElseThrow();
        assertEquals( Severity.ERROR, v.severity() );
    }

    @Test
    void cleanMetadataYieldsNoViolations() {
        final Map< String, Object > meta = Map.of(
                "type", "article",
                "status", "active",
                "summary", "A sufficiently long and descriptive summary of this page here.",
                "cluster", "interval-trees",
                "audience", "both" );
        assertTrue( validator.validate( meta, ValidationCtx.lenient() ).isEmpty() );
    }
}

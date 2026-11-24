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
import com.wikantik.api.frontmatter.schema.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontmatterSchemaLiveValuesTest {

    private static final String RAW_40 = "{{wiki:articles|round=100}} pages of notes";
    private static final String EXPANDED_60 = "1,100+ pages of reliable engineering notes for all developers";

    private final SchemaDrivenFrontmatterValidator expanding = new SchemaDrivenFrontmatterValidator(
            FrontmatterSchema.defaultSchema(), s -> s.replace( "{{wiki:articles|round=100}} pages of notes", EXPANDED_60 ) );
    private final SchemaDrivenFrontmatterValidator plain =
            new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() );

    private static List< FieldViolation > codes( final List< FieldViolation > vs, final String code ) {
        return vs.stream().filter( v -> v.code().equals( code ) ).toList();
    }

    @Test
    void lengthWarningMeasuresTheExpandedSummary() {
        assertEquals( 42, RAW_40.length() );
        assertTrue( EXPANDED_60.length() >= 50 && EXPANDED_60.length() <= 160 );
        final Map< String, Object > md = Map.of( "summary", RAW_40 );
        assertTrue( codes( expanding.validate( md, ValidationCtx.lenient() ), "summary.length" ).isEmpty(),
                "expanded length is in range, so no warning" );
        assertEquals( 1, codes( plain.validate( md, ValidationCtx.lenient() ), "summary.length" ).size(),
                "without an expander the raw text is measured" );
    }

    @Test
    void unknownTokenNameWarnsAndNamesIt() {
        final List< FieldViolation > vs = plain.validate(
                Map.of( "summary", "About {{wiki:artcles}} articles in the wiki, written for working engineers." ),
                ValidationCtx.lenient() );
        final FieldViolation v = codes( vs, "livevalues.unknown" ).get( 0 );
        assertEquals( Severity.WARNING, v.severity() );
        assertEquals( "summary", v.field() );
        assertTrue( v.message().contains( "artcles" ) );
    }

    @Test
    void wellFormedClusterNameNeverWarnsEvenIfClusterIsMissing() {
        final List< FieldViolation > vs = plain.validate(
                Map.of( "summary", "{{wiki:cluster.not-yet/created.pages}} pages, plus {{wiki:articles}} articles overall." ),
                ValidationCtx.lenient() );
        assertTrue( codes( vs, "livevalues.unknown" ).isEmpty() );
    }

    @Test
    void malformedClusterNameWarns() {
        final List< FieldViolation > vs = plain.validate(
                Map.of( "summary", "{{wiki:cluster.a/b/c.pages}} and {{wiki:cluster.x.bogus}} for the record, everyone." ),
                ValidationCtx.lenient() );
        assertEquals( 2, codes( vs, "livevalues.unknown" ).size() );
    }

    @Test
    void malformedRoundWarns() {
        for ( final String bad : List.of( "{{wiki:articles|round=0}}", "{{wiki:articles|round=x}}",
                "{{wiki:articles|bogus=1}}", "{{wiki:}}", "{{wiki:articles" ) ) {
            final List< FieldViolation > vs = plain.validate(
                    Map.of( "summary", "Counting " + bad + " of the things in this wiki, as a long enough text." ),
                    ValidationCtx.lenient() );
            final List< FieldViolation > malformed = codes( vs, "livevalues.malformed" );
            assertEquals( 1, malformed.size(), bad );
            assertEquals( Severity.WARNING, malformed.get( 0 ).severity() );
        }
    }

    @Test
    void tokenInAnotherFieldWarnsThatItWillNotExpand() {
        final List< FieldViolation > vs = plain.validate(
                Map.of( "title", "{{wiki:articles}} things", "tags", List.of( "{{wiki:pages}}" ) ),
                ValidationCtx.lenient() );
        final List< FieldViolation > notExpanded = codes( vs, "livevalues.field" );
        assertEquals( 2, notExpanded.size() );
        assertTrue( notExpanded.stream().allMatch( v -> v.severity() == Severity.WARNING ) );
    }

    @Test
    void goodSummaryAndPlainFieldsAreSilent() {
        final List< FieldViolation > vs = plain.validate(
                Map.of( "summary", "A guide with {{wiki:articles|round=100|else=many}} articles, kept up to date automatically." ),
                ValidationCtx.lenient() );
        assertFalse( vs.stream().anyMatch( v -> v.code().startsWith( "livevalues." ) ) );
    }
}

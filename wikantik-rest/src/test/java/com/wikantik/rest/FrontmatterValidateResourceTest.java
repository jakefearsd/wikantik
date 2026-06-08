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
package com.wikantik.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.ValidationCtx;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FrontmatterValidateResourceTest {

    private final SchemaDrivenFrontmatterValidator validator =
            new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() );

    private JsonObject json( final String s ) {
        return JsonParser.parseString( s ).getAsJsonObject();
    }

    @SuppressWarnings( "unchecked" )
    private List< FieldViolation > violations( final Map< String, Object > payload ) {
        return ( List< FieldViolation > ) payload.get( "violations" );
    }

    @Test
    void parsesFrontmatterYamlAndValidates() {
        final Map< String, Object > payload = FrontmatterValidateResource.validatePayload(
                validator, ValidationCtx.lenient(),
                json( "{\"frontmatter\":\"type: article\\nstatus: published\"}" ) );
        assertTrue( violations( payload ).stream().anyMatch( v -> v.field().equals( "status" ) ),
                "non-canonical status surfaces as a warning" );
    }

    @Test
    void malformedYamlReturnsYamlViolationAndNullMetadata() {
        final Map< String, Object > payload = FrontmatterValidateResource.validatePayload(
                validator, ValidationCtx.lenient(),
                json( "{\"frontmatter\":\"tags: [a, b\"}" ) );
        assertNull( payload.get( "metadata" ) );
        assertEquals( "__yaml__", violations( payload ).get( 0 ).field() );
    }

    @Test
    void validatesMetadataObjectDirectly() {
        final Map< String, Object > payload = FrontmatterValidateResource.validatePayload(
                validator, ValidationCtx.lenient(),
                json( "{\"metadata\":{\"audience\":\"robots\"}}" ) );
        assertTrue( violations( payload ).stream().anyMatch( v -> v.field().equals( "audience" ) ) );
    }
}

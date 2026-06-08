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

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.wikantik.api.frontmatter.FrontmatterParseException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;
import com.wikantik.api.frontmatter.schema.Severity;
import com.wikantik.frontmatter.schema.SchemaDrivenFrontmatterValidator;
import com.wikantik.frontmatter.schema.ValidationCtx;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@code POST /api/frontmatter/validate} — parses YAML (or accepts a metadata object) and runs the
 * schema validator <em>without saving</em>. Returns {@code { metadata, violations }}. Powers the
 * editor's Raw→Form sync (authoritative parse via the one SnakeYAML implementation, so no divergent
 * client YAML parser) and optional pre-save warning preview.
 */
public class FrontmatterValidateResource extends RestServletBase {

    private static final long serialVersionUID = 1L;

    private final SchemaDrivenFrontmatterValidator validator =
            new SchemaDrivenFrontmatterValidator( FrontmatterSchema.defaultSchema() );

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) {
            return; // 400 already sent
        }
        sendJson( response, validatePayload( validator, buildCtx( request ), body ) );
    }

    private ValidationCtx buildCtx( final HttpServletRequest request ) {
        // Advisory dry-run: page resolution is engine-backed; trust + non-canonical severity are
        // intentionally permissive here — the save filter is the authority for blocking enforcement.
        final Predicate< String > pageResolves = name -> {
            try {
                return getSubsystems().page().pages().wikiPageExists( name );
            } catch ( final RuntimeException e ) {
                return true; // never fail a dry-run on a resolution hiccup
            }
        };
        return new ValidationCtx( pageResolves, a -> true, Severity.WARNING );
    }

    /** Pure core: parse {@code frontmatter} (or read {@code metadata}) and validate. No I/O. */
    static Map< String, Object > validatePayload( final SchemaDrivenFrontmatterValidator validator,
                                                  final ValidationCtx ctx, final JsonObject body ) {
        Map< String, Object > metadata;
        List< FieldViolation > violations;

        if ( body.has( "frontmatter" ) && !body.get( "frontmatter" ).isJsonNull() ) {
            final String yaml = body.get( "frontmatter" ).getAsString();
            String wrapped = yaml.startsWith( "---" ) ? yaml : "---\n" + yaml + "\n---\n";
            if ( !wrapped.endsWith( "\n" ) ) {
                wrapped += "\n";
            }
            try {
                final ParsedPage parsed = FrontmatterParser.parseStrict( wrapped );
                metadata = parsed.metadata();
                violations = validator.validate( metadata, ctx );
            } catch ( final FrontmatterParseException e ) {
                metadata = null;
                final String loc = e.line() > 0
                        ? " (line " + e.line() + ( e.column() > 0 ? ", column " + e.column() : "" ) + ")"
                        : "";
                violations = List.of( FieldViolation.of( "__yaml__", Severity.ERROR, "yaml.parse",
                        "Malformed YAML frontmatter: " + e.getMessage() + loc ) );
            }
        } else if ( body.has( "metadata" ) && body.get( "metadata" ).isJsonObject() ) {
            metadata = toMap( body.getAsJsonObject( "metadata" ) );
            violations = validator.validate( metadata, ctx );
        } else {
            metadata = Map.of();
            violations = List.of();
        }

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "metadata", metadata );
        out.put( "violations", violations );
        return out;
    }

    private static Map< String, Object > toMap( final JsonObject obj ) {
        return GSON.fromJson( obj, new TypeToken< Map< String, Object > >() {}.getType() );
    }
}

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

import com.wikantik.api.frontmatter.schema.FieldSpec;
import com.wikantik.api.frontmatter.schema.FrontmatterSchema;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/frontmatter-schema} — serves the server-authoritative
 * {@link FrontmatterSchema} as JSON so the React editor renders its widgets and advisory hints from
 * the same descriptor the save-time validator enforces. Read-only and cacheable.
 */
public class FrontmatterSchemaResource extends RestServletBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        response.setHeader( "Cache-Control", "public, max-age=300" );
        sendJson( response, schemaPayload() );
    }

    /** Builds the JSON payload {@code { fields: [ {key,label,widget,...} ] }} from the default schema. */
    static Map< String, Object > schemaPayload() {
        final List< Map< String, Object > > fields = new ArrayList<>();
        for ( final FieldSpec f : FrontmatterSchema.defaultSchema().fields() ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "key", f.key() );
            m.put( "label", f.label() );
            m.put( "widget", f.widget().name() );
            m.put( "canonicalValues", f.canonicalValues() );
            m.put( "open", f.open() );
            m.put( "minLen", f.minLen() );
            m.put( "maxLen", f.maxLen() );
            m.put( "pattern", f.pattern() );
            m.put( "suggestionMap", f.suggestionMap() );
            fields.add( m );
        }
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "fields", fields );
        return out;
    }
}

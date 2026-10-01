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

import com.wikantik.api.frontmatter.schema.PageTemplates;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code GET /api/page-templates} — the built-in per-type page templates. Read-only and cacheable. */
public class PageTemplatesResource extends RestServletBase {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        response.setHeader( "Cache-Control", "public, max-age=300" );
        sendJson( response, templatesPayload() );
    }

    static Map< String, Object > templatesPayload() {
        final List< Map< String, Object > > out = new ArrayList<>();
        for ( final PageTemplates.Template t : PageTemplates.all() ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "type", t.type() );
            m.put( "label", t.label() );
            m.put( "description", t.description() );
            m.put( "metadata", t.metadata() );
            m.put( "body", t.body() );
            out.add( m );
        }
        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "templates", out );
        return payload;
    }
}

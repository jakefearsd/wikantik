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
package com.wikantik.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class OpenApiDocumentTest {

    @Mock HttpServletRequest request;

    @Test
    void rendersValidOpenApi31Document() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.public.baseURL", "https://wiki.example.com" );
        final ToolsConfig config = new ToolsConfig( props );

        final String json = OpenApiDocument.render( request, config );
        final JsonObject root = JsonParser.parseString( json ).getAsJsonObject();

        assertEquals( "3.1.0", root.get( "openapi" ).getAsString() );
        assertTrue( root.has( "info" ) );
        assertTrue( root.has( "paths" ) );
        assertTrue( root.has( "components" ) );
    }

    @Test
    void exposesSearchAndGetPageOperations() {
        final String json = OpenApiDocument.render( request, new ToolsConfig( new Properties() ) );
        final JsonObject root = JsonParser.parseString( json ).getAsJsonObject();
        final JsonObject paths = root.getAsJsonObject( "paths" );

        assertTrue( paths.has( "/search_wiki" ) );
        assertTrue( paths.has( "/page/{name}" ) );
        assertEquals( "search_wiki", paths.getAsJsonObject( "/search_wiki" )
                .getAsJsonObject( "post" ).get( "operationId" ).getAsString() );
        assertEquals( "get_page", paths.getAsJsonObject( "/page/{name}" )
                .getAsJsonObject( "get" ).get( "operationId" ).getAsString() );
    }

    @Test
    void declaresBearerSecurityScheme() {
        final String json = OpenApiDocument.render( request, new ToolsConfig( new Properties() ) );
        final JsonObject root = JsonParser.parseString( json ).getAsJsonObject();
        final JsonObject scheme = root.getAsJsonObject( "components" )
                .getAsJsonObject( "securitySchemes" )
                .getAsJsonObject( "bearerAuth" );
        assertEquals( "http", scheme.get( "type" ).getAsString() );
        assertEquals( "bearer", scheme.get( "scheme" ).getAsString() );
    }

    @Test
    void usesPublicBaseUrlForServerWhenConfigured() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.public.baseURL", "https://wiki.example.com/" );
        final ToolsConfig config = new ToolsConfig( props );

        final String json = OpenApiDocument.render( request, config );
        final JsonObject root = JsonParser.parseString( json ).getAsJsonObject();
        final String url = root.getAsJsonArray( "servers" )
                .get( 0 ).getAsJsonObject().get( "url" ).getAsString();
        assertEquals( "https://wiki.example.com/tools", url );
    }

    @Test
    void fallsBackToRequestHostWhenNoBaseUrl() {
        when( request.getScheme() ).thenReturn( "http" );
        when( request.getServerName() ).thenReturn( "localhost" );
        when( request.getServerPort() ).thenReturn( 8080 );
        final String json = OpenApiDocument.render( request, new ToolsConfig( new Properties() ) );
        final JsonObject root = JsonParser.parseString( json ).getAsJsonObject();
        final String url = root.getAsJsonArray( "servers" )
                .get( 0 ).getAsJsonObject().get( "url" ).getAsString();
        assertEquals( "http://localhost:8080/tools", url );
    }
}

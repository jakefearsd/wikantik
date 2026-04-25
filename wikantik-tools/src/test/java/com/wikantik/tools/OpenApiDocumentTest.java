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

    @Test
    void searchOperationCarriesWorkedExamplesForAgents() {
        // AG-Phase 6: every tool's request/response carries a worked example so
        // OpenAPI clients see a concrete payload, not just a typed schema.
        final String json = OpenApiDocument.render( request, new ToolsConfig( new Properties() ) );
        final JsonObject root = JsonParser.parseString( json ).getAsJsonObject();
        final JsonObject searchOp = root.getAsJsonObject( "paths" )
                .getAsJsonObject( "/search_wiki" )
                .getAsJsonObject( "post" );

        // Request body example
        final JsonObject reqContent = searchOp.getAsJsonObject( "requestBody" )
                .getAsJsonObject( "content" )
                .getAsJsonObject( "application/json" );
        assertTrue( reqContent.has( "example" ),
                "search_wiki request body must include a worked example for agent first-call success" );

        // 200 response example
        final JsonObject okContent = searchOp.getAsJsonObject( "responses" )
                .getAsJsonObject( "200" )
                .getAsJsonObject( "content" )
                .getAsJsonObject( "application/json" );
        assertTrue( okContent.has( "example" ),
                "search_wiki 200 response must include a worked example" );
    }

    @Test
    void getPageOperationCarriesWorkedExamplesForAgents() {
        final String json = OpenApiDocument.render( request, new ToolsConfig( new Properties() ) );
        final JsonObject root = JsonParser.parseString( json ).getAsJsonObject();
        final JsonObject getPageOp = root.getAsJsonObject( "paths" )
                .getAsJsonObject( "/page/{name}" )
                .getAsJsonObject( "get" );

        // Path/query parameters carry per-parameter examples
        final var params = getPageOp.getAsJsonArray( "parameters" );
        boolean anyParamHasExample = false;
        for ( int i = 0; i < params.size(); i++ ) {
            if ( params.get( i ).getAsJsonObject().has( "example" ) ) {
                anyParamHasExample = true;
                break;
            }
        }
        assertTrue( anyParamHasExample, "get_page parameters must include at least one example" );

        // 200 response example
        final JsonObject okContent = getPageOp.getAsJsonObject( "responses" )
                .getAsJsonObject( "200" )
                .getAsJsonObject( "content" )
                .getAsJsonObject( "application/json" );
        assertTrue( okContent.has( "example" ),
                "get_page 200 response must include a worked example" );
    }

    @Test
    void searchResultSchemaIncludesContributingChunksAndRelatedPages() {
        final String json = OpenApiDocument.render( request, new ToolsConfig( new Properties() ) );
        final JsonObject root = JsonParser.parseString( json ).getAsJsonObject();
        final JsonObject schemas = root.getAsJsonObject( "components" )
                .getAsJsonObject( "schemas" );

        assertTrue( schemas.has( "ContributingChunk" ), "ContributingChunk schema must be present" );
        assertTrue( schemas.has( "RelatedPageHint" ), "RelatedPageHint schema must be present" );

        final JsonObject searchResultProps = schemas.getAsJsonObject( "SearchResult" )
                .getAsJsonObject( "properties" );
        assertTrue( searchResultProps.has( "contributingChunks" ), "SearchResult must have contributingChunks" );
        assertTrue( searchResultProps.has( "relatedPages" ), "SearchResult must have relatedPages" );

        final String chunksRef = searchResultProps.getAsJsonObject( "contributingChunks" )
                .getAsJsonObject( "items" ).get( "$ref" ).getAsString();
        assertEquals( "#/components/schemas/ContributingChunk", chunksRef );

        final String relatedRef = searchResultProps.getAsJsonObject( "relatedPages" )
                .getAsJsonObject( "items" ).get( "$ref" ).getAsString();
        assertEquals( "#/components/schemas/RelatedPageHint", relatedRef );
    }
}

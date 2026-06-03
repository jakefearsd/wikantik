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
package com.wikantik.scim;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * SCIM 2.0 discovery endpoints (RFC 7643 §8):
 * <ul>
 *   <li>GET /scim/v2/ServiceProviderConfig — capability advertisement</li>
 *   <li>GET /scim/v2/Schemas             — User schema descriptor</li>
 *   <li>GET /scim/v2/ResourceTypes       — User resource type</li>
 * </ul>
 * All responses are static JSON served as {@code application/scim+json}.
 */
public class ScimDiscoveryResource extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( ScimDiscoveryResource.class );
    private static final String CONTENT_TYPE = "application/scim+json";

    // ---- Static SCIM discovery payloads (RFC 7643 §8) ----------------------

    private static final String SERVICE_PROVIDER_CONFIG =
        "{\n"
        + "  \"schemas\": [\"urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig\"],\n"
        + "  \"documentationUri\": \"\",\n"
        + "  \"patch\": { \"supported\": true },\n"
        + "  \"bulk\":  { \"supported\": false, \"maxOperations\": 0, \"maxPayloadSize\": 0 },\n"
        + "  \"filter\": { \"supported\": true, \"maxResults\": 1000 },\n"
        + "  \"changePassword\": { \"supported\": false },\n"
        + "  \"sort\":           { \"supported\": false },\n"
        + "  \"etag\":           { \"supported\": false },\n"
        + "  \"authenticationSchemes\": [\n"
        + "    {\n"
        + "      \"name\": \"OAuth Bearer Token\",\n"
        + "      \"description\": \"Authentication scheme using the OAuth Bearer Token Standard\",\n"
        + "      \"specUri\": \"http://www.rfc-editor.org/info/rfc6750\",\n"
        + "      \"type\": \"oauthbearertoken\",\n"
        + "      \"primary\": true\n"
        + "    }\n"
        + "  ],\n"
        + "  \"meta\": {\n"
        + "    \"resourceType\": \"ServiceProviderConfig\",\n"
        + "    \"location\": \"/scim/v2/ServiceProviderConfig\"\n"
        + "  }\n"
        + "}";

    private static final String SCHEMAS =
        "{\n"
        + "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n"
        + "  \"totalResults\": 1,\n"
        + "  \"itemsPerPage\": 1,\n"
        + "  \"startIndex\": 1,\n"
        + "  \"Resources\": [\n"
        + "    {\n"
        + "      \"id\": \"urn:ietf:params:scim:schemas:core:2.0:User\",\n"
        + "      \"name\": \"User\",\n"
        + "      \"description\": \"User account\",\n"
        + "      \"schemas\": [\"urn:ietf:params:scim:meta:schema\"],\n"
        + "      \"attributes\": [\n"
        + "        { \"name\": \"userName\",    \"type\": \"string\",  \"required\": true,  \"caseExact\": false, \"mutability\": \"readWrite\", \"returned\": \"default\", \"uniqueness\": \"server\" },\n"
        + "        { \"name\": \"externalId\",  \"type\": \"string\",  \"required\": false, \"caseExact\": false, \"mutability\": \"readWrite\", \"returned\": \"default\", \"uniqueness\": \"none\" },\n"
        + "        { \"name\": \"displayName\", \"type\": \"string\",  \"required\": false, \"caseExact\": false, \"mutability\": \"readWrite\", \"returned\": \"default\", \"uniqueness\": \"none\" },\n"
        + "        { \"name\": \"active\",      \"type\": \"boolean\", \"required\": false, \"mutability\": \"readWrite\", \"returned\": \"default\", \"uniqueness\": \"none\" },\n"
        + "        { \"name\": \"name\",        \"type\": \"complex\",  \"required\": false, \"mutability\": \"readWrite\", \"returned\": \"default\",\n"
        + "          \"subAttributes\": [\n"
        + "            { \"name\": \"formatted\",   \"type\": \"string\", \"required\": false, \"mutability\": \"readWrite\", \"returned\": \"default\" },\n"
        + "            { \"name\": \"givenName\",   \"type\": \"string\", \"required\": false, \"mutability\": \"readWrite\", \"returned\": \"default\" },\n"
        + "            { \"name\": \"familyName\",  \"type\": \"string\", \"required\": false, \"mutability\": \"readWrite\", \"returned\": \"default\" }\n"
        + "          ]\n"
        + "        },\n"
        + "        { \"name\": \"emails\",      \"type\": \"complex\",  \"required\": false, \"multiValued\": true, \"mutability\": \"readWrite\", \"returned\": \"default\",\n"
        + "          \"subAttributes\": [\n"
        + "            { \"name\": \"value\",   \"type\": \"string\",  \"required\": false, \"mutability\": \"readWrite\", \"returned\": \"default\" },\n"
        + "            { \"name\": \"primary\", \"type\": \"boolean\", \"required\": false, \"mutability\": \"readWrite\", \"returned\": \"default\" }\n"
        + "          ]\n"
        + "        }\n"
        + "      ],\n"
        + "      \"meta\": { \"resourceType\": \"Schema\", \"location\": \"/scim/v2/Schemas/urn:ietf:params:scim:schemas:core:2.0:User\" }\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    private static final String RESOURCE_TYPES =
        "{\n"
        + "  \"schemas\": [\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],\n"
        + "  \"totalResults\": 1,\n"
        + "  \"itemsPerPage\": 1,\n"
        + "  \"startIndex\": 1,\n"
        + "  \"Resources\": [\n"
        + "    {\n"
        + "      \"schemas\": [\"urn:ietf:params:scim:schemas:core:2.0:ResourceType\"],\n"
        + "      \"id\": \"User\",\n"
        + "      \"name\": \"User\",\n"
        + "      \"endpoint\": \"/Users\",\n"
        + "      \"description\": \"User account\",\n"
        + "      \"schema\": \"urn:ietf:params:scim:schemas:core:2.0:User\",\n"
        + "      \"schemaExtensions\": [],\n"
        + "      \"meta\": { \"resourceType\": \"ResourceType\", \"location\": \"/scim/v2/ResourceTypes/User\" }\n"
        + "    }\n"
        + "  ]\n"
        + "}";

    // -------------------------------------------------------------------------

    @Override
    protected void doGet( final HttpServletRequest req, final HttpServletResponse resp )
            throws IOException {
        final String path = req.getServletPath();
        final String body;
        if ( path.endsWith( "ServiceProviderConfig" ) ) {
            body = SERVICE_PROVIDER_CONFIG;
        } else if ( path.endsWith( "Schemas" ) ) {
            body = SCHEMAS;
        } else if ( path.endsWith( "ResourceTypes" ) ) {
            body = RESOURCE_TYPES;
        } else {
            LOG.warn( "ScimDiscoveryResource: unexpected path '{}' — returning 404", path );
            resp.setStatus( 404 );
            resp.setContentType( CONTENT_TYPE );
            resp.getWriter().write( "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:Error\"],"
                    + "\"status\":\"404\",\"detail\":\"Not found\"}" );
            return;
        }
        resp.setContentType( CONTENT_TYPE );
        resp.getWriter().write( body );
    }
}

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
package com.wikantik.connectors.config;

import com.google.gson.JsonObject;
import com.wikantik.connectors.confluence.ConfluenceConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation and JSON &lt;-&gt; typed-config translation for the {@code confluence} connector type.
 * Package-private unit dispatched from {@link ConnectorConfigCodec}; see that class for the public
 * API and design notes.
 */
final class ConfluenceCodec {

    private ConfluenceCodec() {}

    static Map< String, String > validate( final JsonObject config ) {
        final Map< String, String > errors = new LinkedHashMap<>();
        ConfigValidationSupport.requireHttpUrl( errors, config, "base_url" );
        ConfigValidationSupport.requireNonBlank( errors, config, "space_key" );
        ConfigValidationSupport.requireNonBlank( errors, config, "email" );
        ConfigValidationSupport.requireMinInt( errors, config, "max_pages", 500, 0 );
        return errors;
    }

    static ConfluenceConfig build( final JsonObject config, final boolean forTest ) {
        return new ConfluenceConfig(
            JsonFieldSupport.str( config, "base_url" ),
            JsonFieldSupport.str( config, "space_key" ),
            JsonFieldSupport.str( config, "email" ),
            JsonFieldSupport.clampMax( JsonFieldSupport.intVal( config, "max_pages", 500 ), forTest ) );
    }

    static JsonObject toJson( final ConfluenceConfig c ) {
        final JsonObject json = new JsonObject();
        json.addProperty( "base_url", c.baseUrl() );
        json.addProperty( "space_key", c.spaceKey() );
        json.addProperty( "email", c.email() );
        json.addProperty( "max_pages", c.maxPages() );
        return json;
    }
}

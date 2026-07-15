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
import com.wikantik.connectors.gdrive.DriveConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation and JSON &lt;-&gt; typed-config translation for the {@code gdrive} connector type.
 * Package-private unit dispatched from {@link ConnectorConfigCodec}; see that class for the public
 * API and design notes.
 */
final class DriveCodec {

    private DriveCodec() {}

    static Map< String, String > validate( final JsonObject config ) {
        final Map< String, String > errors = new LinkedHashMap<>();
        if ( JsonFieldSupport.strList( config, "folder_ids" ).isEmpty() ) {
            errors.put( "folder_ids", "at least one folder id is required" );
        }
        ConfigValidationSupport.requireNonBlank( errors, config, "client_id" );
        ConfigValidationSupport.requireHttpUrl( errors, config, "redirect_uri" );
        ConfigValidationSupport.requireMinInt( errors, config, "max_files", 500, 0 );
        // client_secret is intentionally never validated here — it is not part of the admin-UI
        // config JSON; it lives in the CredentialStore and is resolved at assembly (Task 5).
        return errors;
    }

    static DriveConfig build( final JsonObject config, final boolean forTest ) {
        return new DriveConfig(
            JsonFieldSupport.strList( config, "folder_ids" ),
            JsonFieldSupport.clampMax( JsonFieldSupport.intVal( config, "max_files", 500 ), forTest ),
            JsonFieldSupport.str( config, "client_id" ),
            null,   // client_secret lives in the CredentialStore, resolved at assembly (Task 5)
            JsonFieldSupport.str( config, "redirect_uri" ),
            JsonFieldSupport.strOr( config, "export_mime", "text/markdown" ) );
    }

    static JsonObject toJson( final DriveConfig c ) {
        final JsonObject json = new JsonObject();
        JsonFieldSupport.putStrList( json, "folder_ids", c.folderIds() );
        json.addProperty( "max_files", c.maxFiles() );
        json.addProperty( "client_id", c.clientId() );
        json.addProperty( "redirect_uri", c.redirectUri() );
        json.addProperty( "export_mime", c.exportMimeType() );
        // client_secret intentionally omitted — lives in the CredentialStore, never round-tripped here.
        return json;
    }
}

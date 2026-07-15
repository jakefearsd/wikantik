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
import com.wikantik.connectors.github.GithubConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validation and JSON &lt;-&gt; typed-config translation for the {@code github} connector type.
 * Package-private unit dispatched from {@link ConnectorConfigCodec}; see that class for the public
 * API and design notes.
 */
final class GithubCodec {

    private static final Pattern REPO_PATTERN = Pattern.compile( "[^/\\s]+/[^/\\s]+" );

    private GithubCodec() {}

    static Map< String, String > validate( final JsonObject config ) {
        final Map< String, String > errors = new LinkedHashMap<>();
        final String repo = JsonFieldSupport.str( config, "repo" );
        if ( repo == null ) {
            errors.put( "repo", "repo is required" );
        } else if ( !REPO_PATTERN.matcher( repo ).matches() ) {
            errors.put( "repo", "must be \"owner/name\"" );
        }
        ConfigValidationSupport.requireMinInt( errors, config, "max_files", 500, 0 );
        return errors;
    }

    static GithubConfig build( final JsonObject config, final boolean forTest ) {
        return new GithubConfig(
            JsonFieldSupport.str( config, "repo" ),
            JsonFieldSupport.str( config, "branch" ),
            JsonFieldSupport.str( config, "path_prefix" ),
            JsonFieldSupport.clampMax( JsonFieldSupport.intVal( config, "max_files", 500 ), forTest ) );
    }

    static JsonObject toJson( final GithubConfig c ) {
        final JsonObject json = new JsonObject();
        json.addProperty( "repo", c.repo() );
        json.addProperty( "branch", c.branch() );
        json.addProperty( "path_prefix", c.pathPrefix() );
        json.addProperty( "max_files", c.maxFiles() );
        return json;
    }
}

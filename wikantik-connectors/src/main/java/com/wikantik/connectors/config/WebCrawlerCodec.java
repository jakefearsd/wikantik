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
import com.wikantik.connectors.web.WebCrawlerConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation and JSON &lt;-&gt; typed-config translation for the {@code webcrawler} connector type.
 * Package-private unit dispatched from {@link ConnectorConfigCodec}; see that class for the public
 * API and design notes.
 */
final class WebCrawlerCodec {

    private WebCrawlerCodec() {}

    static Map< String, String > validate( final JsonObject config ) {
        final Map< String, String > errors = new LinkedHashMap<>();
        ConfigValidationSupport.requireHttpUrlList( errors, config, "seeds" );
        ConfigValidationSupport.requireMinInt( errors, config, "max_pages", 100, 0 );
        ConfigValidationSupport.requireMinInt( errors, config, "max_depth", 3, 1 );
        ConfigValidationSupport.requireMinLong( errors, config, "delay_ms", 1000L, 0L );
        return errors;
    }

    static WebCrawlerConfig build( final JsonObject config, final boolean forTest ) {
        return new WebCrawlerConfig(
            JsonFieldSupport.strList( config, "seeds" ),
            JsonFieldSupport.boolVal( config, "same_host_only", true ),
            JsonFieldSupport.str( config, "path_prefix" ),
            JsonFieldSupport.clampMax( JsonFieldSupport.intVal( config, "max_pages", 100 ), forTest ),
            forTest ? 1 : JsonFieldSupport.intVal( config, "max_depth", 3 ),
            forTest ? 0L : JsonFieldSupport.longVal( config, "delay_ms", 1000L ),
            JsonFieldSupport.strOr( config, "user_agent", JsonFieldSupport.DEFAULT_USER_AGENT ),
            JsonFieldSupport.boolVal( config, "respect_robots", true ) );
    }

    static JsonObject toJson( final WebCrawlerConfig c ) {
        final JsonObject json = new JsonObject();
        JsonFieldSupport.putStrList( json, "seeds", c.seeds() );
        json.addProperty( "same_host_only", c.sameHostOnly() );
        json.addProperty( "path_prefix", c.pathPrefix() );
        json.addProperty( "max_pages", c.maxPages() );
        json.addProperty( "max_depth", c.maxDepth() );
        json.addProperty( "delay_ms", c.delayMs() );
        json.addProperty( "user_agent", c.userAgent() );
        json.addProperty( "respect_robots", c.respectRobots() );
        return json;
    }
}

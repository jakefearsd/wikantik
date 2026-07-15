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
import com.wikantik.connectors.web.SitemapConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation and JSON &lt;-&gt; typed-config translation for the {@code sitemap} connector type.
 * Package-private unit dispatched from {@link ConnectorConfigCodec}; see that class for the public
 * API and design notes.
 */
final class SitemapCodec {

    private SitemapCodec() {}

    static Map< String, String > validate( final JsonObject config ) {
        final Map< String, String > errors = new LinkedHashMap<>();
        ConfigValidationSupport.requireHttpUrlList( errors, config, "sitemap_urls" );
        ConfigValidationSupport.requireMinInt( errors, config, "max_pages", 500, 0 );
        ConfigValidationSupport.requireMinLong( errors, config, "delay_ms", 1000L, 0L );
        return errors;
    }

    static SitemapConfig build( final JsonObject config, final boolean forTest ) {
        return new SitemapConfig(
            JsonFieldSupport.strList( config, "sitemap_urls" ),
            JsonFieldSupport.clampMax( JsonFieldSupport.intVal( config, "max_pages", 500 ), forTest ),
            forTest ? 0L : JsonFieldSupport.longVal( config, "delay_ms", 1000L ),
            JsonFieldSupport.strOr( config, "user_agent", JsonFieldSupport.DEFAULT_USER_AGENT ),
            JsonFieldSupport.boolVal( config, "respect_robots", true ),
            JsonFieldSupport.boolVal( config, "same_host_only", true ) );
    }

    static JsonObject toJson( final SitemapConfig c ) {
        final JsonObject json = new JsonObject();
        JsonFieldSupport.putStrList( json, "sitemap_urls", c.sitemapUrls() );
        json.addProperty( "max_pages", c.maxPages() );
        json.addProperty( "delay_ms", c.delayMs() );
        json.addProperty( "user_agent", c.userAgent() );
        json.addProperty( "respect_robots", c.respectRobots() );
        json.addProperty( "same_host_only", c.sameHostOnly() );
        return json;
    }
}

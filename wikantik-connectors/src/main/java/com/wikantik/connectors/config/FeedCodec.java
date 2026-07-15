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
import com.wikantik.connectors.web.FeedConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation and JSON &lt;-&gt; typed-config translation for the {@code feed} connector type.
 * Package-private unit dispatched from {@link ConnectorConfigCodec}; see that class for the public
 * API and design notes.
 */
final class FeedCodec {

    private FeedCodec() {}

    static Map< String, String > validate( final JsonObject config ) {
        final Map< String, String > errors = new LinkedHashMap<>();
        ConfigValidationSupport.requireHttpUrlList( errors, config, "feed_urls" );
        ConfigValidationSupport.requireMinInt( errors, config, "max_items", 100, 0 );
        ConfigValidationSupport.requireMinLong( errors, config, "delay_ms", 1000L, 0L );
        return errors;
    }

    static FeedConfig build( final JsonObject config, final boolean forTest ) {
        return new FeedConfig(
            JsonFieldSupport.strList( config, "feed_urls" ),
            JsonFieldSupport.clampMax( JsonFieldSupport.intVal( config, "max_items", 100 ), forTest ),
            JsonFieldSupport.boolVal( config, "fetch_full_articles", true ),
            forTest ? 0L : JsonFieldSupport.longVal( config, "delay_ms", 1000L ),
            JsonFieldSupport.strOr( config, "user_agent", JsonFieldSupport.DEFAULT_USER_AGENT ),
            JsonFieldSupport.boolVal( config, "respect_robots", true ),
            JsonFieldSupport.boolVal( config, "same_host_only", true ) );
    }

    static JsonObject toJson( final FeedConfig c ) {
        final JsonObject json = new JsonObject();
        JsonFieldSupport.putStrList( json, "feed_urls", c.feedUrls() );
        json.addProperty( "max_items", c.maxItems() );
        json.addProperty( "fetch_full_articles", c.fetchFullArticles() );
        json.addProperty( "delay_ms", c.delayMs() );
        json.addProperty( "user_agent", c.userAgent() );
        json.addProperty( "respect_robots", c.respectRobots() );
        json.addProperty( "same_host_only", c.sameHostOnly() );
        return json;
    }
}

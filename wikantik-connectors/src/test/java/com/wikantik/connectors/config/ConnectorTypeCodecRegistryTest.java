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
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorTypeCodecRegistryTest {

    @Test
    void uiTypesAreTheRegisteredCodecs() {
        assertEquals( Set.of( "webcrawler", "sitemap", "feed", "gdrive", "github", "confluence" ),
            ConnectorConfigCodec.UI_TYPES );
    }

    @Test
    void filesystemHasNoCodec() {
        assertTrue( ConnectorConfigCodec.codecFor( "filesystem" ).isEmpty() );
        assertTrue( ConnectorConfigCodec.codecFor( null ).isEmpty() );
    }

    @Test
    void everyCodecReportsTheTypeItIsRegisteredUnder() {
        for ( final String type : ConnectorConfigCodec.UI_TYPES ) {
            assertEquals( type, ConnectorConfigCodec.codecFor( type ).orElseThrow().type() );
        }
    }

    @Test
    void toJsonRejectsAConfigOfTheWrongClassNamingTheType() {
        final JsonObject sitemap = JsonParser.parseString( "{\"sitemap_urls\":[\"https://example.com/s.xml\"]}" ).getAsJsonObject();
        final Object sitemapConfig = ConnectorConfigCodec.toConfig( "sitemap", sitemap );
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
            () -> ConnectorConfigCodec.toJson( "feed", sitemapConfig ) );
        assertTrue( e.getMessage().contains( "feed" ), e.getMessage() );
        assertEquals( "config for feed must be FeedConfig", e.getMessage() );
    }

    @Test
    void toJsonRejectsUnknownType() {
        assertThrows( IllegalArgumentException.class, () -> ConnectorConfigCodec.toJson( "filesystem", new Object() ) );
    }
}

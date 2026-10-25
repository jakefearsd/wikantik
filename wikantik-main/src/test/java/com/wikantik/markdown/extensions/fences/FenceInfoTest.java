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
package com.wikantik.markdown.extensions.fences;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FenceInfoTest {

    @Test
    void matchesSharedFixture() throws Exception {
        final JsonObject fixture = JsonParser.parseString( Files.readString(
                Path.of( "..", "wikantik-frontend", "src", "utils", "__fixtures__", "renderedFences.json" ) ) ).getAsJsonObject();
        final JsonArray cases = fixture.getAsJsonArray( "infoStrings" );
        assertFalse( cases.isEmpty() );
        for ( final JsonElement e : cases ) {
            final JsonObject c = e.getAsJsonObject();
            final String info = c.get( "info" ).isJsonNull() ? null : c.get( "info" ).getAsString();
            final List< String > modifiers = new ArrayList<>();
            for ( final JsonElement m : c.getAsJsonArray( "modifiers" ) ) {
                modifiers.add( m.getAsString() );
            }
            final FenceInfo expected = new FenceInfo( c.get( "language" ).getAsString(),
                    c.get( "example" ).getAsBoolean(), modifiers );
            assertEquals( expected, FenceInfo.parse( info ), "info=" + info );
        }
    }

    @Test
    void nullAndBlankYieldEmptyLanguage() {
        assertEquals( "", FenceInfo.parse( null ).language() );
        assertEquals( "", FenceInfo.parse( "" ).language() );
        assertEquals( "", FenceInfo.parse( "   \t " ).language() );
        assertFalse( FenceInfo.parse( null ).example() );
        assertTrue( FenceInfo.parse( null ).modifiers().isEmpty() );
    }
}

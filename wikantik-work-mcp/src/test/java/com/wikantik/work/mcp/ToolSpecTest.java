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
package com.wikantik.work.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins what each work tool advertises (name, description, input schema, required list, annotations) against goldens
 * under {@code src/test/resources/tools/}, and that the arguments a write tool accepts are exactly the properties its
 * schema declares.
 */
class ToolSpecTest {

    static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    static List< WorkMcpTool > tools() {
        return WorkMcpInitializer.assembleTools( mock( WorkService.class ), mock( WorkDirectory.class ) );
    }

    /** The golden text for one tool: its definition as JSON, property order preserved. */
    static String render( final WorkMcpTool tool ) {
        return PRETTY.toJson( tool.definition() ) + "\n";
    }

    private static String golden( final String name ) throws IOException {
        try ( InputStream in = ToolSpecTest.class.getResourceAsStream( "/tools/" + name + ".json" ) ) {
            assertTrue( in != null, "missing golden for " + name );
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    @Test
    void everyToolsSchemaIsUnchanged() throws IOException {
        assertEquals( 16, tools().size() );
        for ( final WorkMcpTool tool : tools() ) {
            // Compared as JSON trees: the object key order inside one property is not the contract (two of the
            // tools used Map.of there, whose order differs per JVM); the order of the arguments themselves is.
            final String expected = golden( tool.name() );
            assertEquals( JsonParser.parseString( expected ), JsonParser.parseString( render( tool ) ),
                "definition of " + tool.name() );
            assertEquals( argumentOrder( JsonParser.parseString( expected ) ),
                argumentOrder( JsonParser.parseString( render( tool ) ) ), "argument order of " + tool.name() );
        }
    }

    private static List< String > argumentOrder( final JsonElement definition ) {
        return List.copyOf( definition.getAsJsonObject().getAsJsonObject( "inputSchema" )
            .getAsJsonObject( "properties" ).keySet() );
    }

    @Test
    void allowedEqualsSchemaProperties() {
        for ( final WorkMcpTool tool : tools() ) {
            if ( !tool.writes() ) {
                continue;
            }
            final Set< String > advertised = new HashSet<>( properties( tool ).keySet() );
            assertEquals( advertised, tool.allowed(), "allowed arguments of " + tool.name() );
        }
    }

    @Test
    void everyRequiredArgumentIsAdvertised() {
        for ( final WorkMcpTool tool : tools() ) {
            final Object required = tool.definition().inputSchema().get( "required" );
            for ( final Object r : ( List< ? > ) required ) {
                assertTrue( properties( tool ).containsKey( r ), tool.name() + " requires an unadvertised " + r );
            }
        }
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > properties( final WorkMcpTool tool ) {
        return ( Map< String, Object > ) tool.definition().inputSchema().get( "properties" );
    }

    @Test
    void anUnadvertisedArgumentGetsTodaysRefusalText() {
        for ( final WorkMcpTool tool : tools() ) {
            if ( !tool.writes() ) {
                continue;
            }
            final WorkValidationException e = assertThrows( WorkValidationException.class,
                () -> tool.run( null, Map.of( "bogus", "x" ) ), tool.name() );
            assertEquals( "bogus", e.field() );
            assertEquals( "Unknown argument.", e.getMessage() );
        }
    }
}

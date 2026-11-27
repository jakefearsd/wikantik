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

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The declaration builder itself: every step hands back the spec, and what was declared is what is advertised. */
class ToolSpecBuilderTest {

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > properties( final McpSchema.Tool tool ) {
        return ( Map< String, Object > ) tool.inputSchema().get( "properties" );
    }

    @Test
    void aReadingToolIsAnnotatedReadOnlyAndAWritingToolIsNot() {
        final ToolSpec read = ToolSpec.reads( "peek" );
        final ToolSpec write = ToolSpec.writes( "poke" );
        assertEquals( "peek", read.name() );
        assertEquals( false, read.writes() );
        assertEquals( Boolean.TRUE, read.definition().annotations().readOnlyHint() );
        assertEquals( Boolean.TRUE, read.definition().annotations().idempotentHint() );
        assertEquals( "poke", write.name() );
        assertEquals( true, write.writes() );
        assertEquals( Boolean.FALSE, write.definition().annotations().readOnlyHint() );
        assertEquals( Boolean.FALSE, write.definition().annotations().destructiveHint() );
    }

    @Test
    void everyDeclaringStepReturnsTheSameSpecSoCallsChain() {
        final ToolSpec spec = ToolSpec.writes( "t" );
        assertSame( spec, spec.description( "d" ) );
        assertSame( spec, spec.arg( "a", "string", "an a", "x" ) );
        assertSame( spec, spec.requiredArg( "b", "integer", "a b", 1 ) );
        assertSame( spec, spec.arg( "c", Map.of( "type", "array" ), false ) );
        assertSame( spec, spec.arg( "d", Map.of( "type", "array" ), true ) );
    }

    @Test
    void theDefinitionCarriesTheDescriptionTheArgumentsInOrderAndOnlyTheRequiredOnesAsRequired() {
        final McpSchema.Tool tool = ToolSpec.writes( "t" ).description( "does t" )
            .requiredArg( "b", "integer", "a b", 1 )
            .arg( "a", "string", "an a", "x" )
            .arg( "c", Map.of( "type", "array" ), true )
            .arg( "z", Map.of( "type", "array" ), false )
            .definition();
        assertEquals( "t", tool.name() );
        assertEquals( "does t", tool.description() );
        assertEquals( "object", tool.inputSchema().get( "type" ) );
        assertEquals( List.of( "b", "a", "c", "z" ), List.copyOf( properties( tool ).keySet() ) );
        assertEquals( List.of( "b", "c" ), tool.inputSchema().get( "required" ) );
        assertEquals( Map.of( "type", "string", "description", "an a", "examples", List.of( "x" ) ),
            properties( tool ).get( "a" ) );
    }

    @Test
    void anOptionalArgumentIsNeverRequired() {
        final McpSchema.Tool tool = ToolSpec.reads( "t" ).arg( "a", "string", "an a", "x" ).definition();
        assertEquals( List.of(), tool.inputSchema().get( "required" ) );
    }

    @Test
    void theAcceptedArgumentsAreExactlyTheDeclaredOnes() {
        assertEquals( Set.of( "a", "b" ), ToolSpec.writes( "t" ).arg( "a", "string", "d", "x" )
            .requiredArg( "b", "string", "d", "y" ).allowed() );
        assertEquals( Set.of(), ToolSpec.reads( "t" ).allowed() );
    }
}

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
package com.wikantik.mcp;

import java.util.List;
import java.util.Map;

/**
 * Test-only accessors for the raw JSON-Schema map on an MCP tool definition.
 *
 * <p>mcp-sdk 2.0.0 replaced the typed {@code McpSchema.JsonSchema} returned by
 * {@code Tool.inputSchema()} with a plain {@code Map<String, Object>}, dropping the
 * {@code required()} / {@code properties()} accessors these assertions were written
 * against. Rather than scatter unchecked casts across every tool test, the two reads
 * live here — and both are null-safe, so "no required fields" reads the same whether
 * the schema omits the key or carries an empty list.
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    /** The schema's {@code required} field names; empty when the key is absent. */
    @SuppressWarnings( "unchecked" )
    public static List< String > required( final Map< String, Object > schema ) {
        final Object value = schema == null ? null : schema.get( "required" );
        return value instanceof List ? ( List< String > ) value : List.of();
    }

    /** The schema's {@code properties} object; empty when the key is absent. */
    @SuppressWarnings( "unchecked" )
    public static Map< String, Object > properties( final Map< String, Object > schema ) {
        final Object value = schema == null ? null : schema.get( "properties" );
        return value instanceof Map ? ( Map< String, Object > ) value : Map.of();
    }
}

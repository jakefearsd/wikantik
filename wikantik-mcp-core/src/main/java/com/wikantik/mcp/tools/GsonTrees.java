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
package com.wikantik.mcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Gson tree as plain Java values, for a tool result's {@code structuredContent}: the MCP SDK serialises that field
 * with Jackson, which cannot write a Gson {@link JsonElement} ({@link McpToolUtils#jsonResult}).
 */
final class GsonTrees {

    private static final BigDecimal LONG_LIMIT = BigDecimal.valueOf( Long.MAX_VALUE );

    private GsonTrees() {
    }

    /** {@code tree} as plain Java values; an integral number becomes a {@code Long}, any other a {@code Double}. */
    static Object plain( final JsonElement tree ) {
        if( tree == null || tree.isJsonNull() ) {
            return null;
        }
        if( tree.isJsonObject() ) {
            final Map< String, Object > out = new LinkedHashMap<>();
            for( final Map.Entry< String, JsonElement > e : tree.getAsJsonObject().entrySet() ) {
                out.put( e.getKey(), plain( e.getValue() ) );
            }
            return out;
        }
        if( tree.isJsonArray() ) {
            final List< Object > out = new ArrayList<>();
            for( final JsonElement e : tree.getAsJsonArray() ) {
                out.add( plain( e ) );
            }
            return out;
        }
        final JsonPrimitive p = tree.getAsJsonPrimitive();
        if( p.isBoolean() ) {
            return p.getAsBoolean();
        }
        if( p.isNumber() ) {
            final BigDecimal n = p.getAsBigDecimal();
            return n.stripTrailingZeros().scale() <= 0 && n.abs().compareTo( LONG_LIMIT ) <= 0
                ? (Object) n.longValueExact() : (Object) n.doubleValue();
        }
        return p.getAsString();
    }
}

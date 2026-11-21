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

import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkValidationException;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reading and checking tool arguments, and describing them in a tool's input schema. */
final class WorkMcpArgs {

    private WorkMcpArgs() {
    }

    /** An optional integer argument (a JSON number may arrive as an integer or a double); null when absent. */
    static Integer intArg( final Map< String, Object > args, final String key ) {
        final Object v = args.get( key );
        if ( v == null ) {
            return null;
        }
        if ( v instanceof Number n ) {
            return n.intValue();
        }
        throw new WorkValidationException( key, "must be a number." );
    }

    /** An optional string argument; null when absent. A value of any other type is a validation error. */
    static String stringArg( final Map< String, Object > args, final String key ) {
        final Object v = args.get( key );
        if ( v == null ) {
            return null;
        }
        if ( v instanceof String s ) {
            return s;
        }
        throw new WorkValidationException( key, "must be a string." );
    }

    /** An optional list-of-strings argument: null when absent, an empty list for null; anything else is an error. */
    static List< String > stringListArg( final Map< String, Object > args, final String key ) {
        if ( !args.containsKey( key ) ) {
            return null;
        }
        final Object v = args.get( key );
        if ( v == null ) {
            return List.of();
        }
        if ( v instanceof List< ? > list ) {
            final List< String > out = new ArrayList<>();
            for ( final Object e : list ) {
                if ( !( e instanceof String s ) ) {
                    throw new WorkValidationException( key, "must be a list of labels." );
                }
                out.add( s );
            }
            return out;
        }
        throw new WorkValidationException( key, "must be a list of labels." );
    }

    /** The input schema of a {@code labels} argument. */
    static Map< String, Object > labelsSchema() {
        final Map< String, Object > p = new LinkedHashMap<>();
        p.put( "type", "array" );
        p.put( "items", Map.of( "type", "string" ) );
        p.put( "description", "Labels: lowercase letters, digits, dots, dashes and underscores, at most 10. "
            + "The whole set is replaced." );
        p.put( "examples", List.of( List.of( "ui", "billing" ) ) );
        return p;
    }

    /** The {@code key} argument; a missing or blank one is the same answer as an unknown item. */
    static String itemKey( final Map< String, Object > args ) {
        final String raw = stringArg( args, "key" );
        if ( raw == null || raw.isBlank() ) {
            throw new WorkNotFoundException( "No item key." );
        }
        return raw.trim();
    }

    /** A required numeric id argument (a change, a deployment): missing or not a whole number is an error by name. */
    static long idArg( final Map< String, Object > args, final String key ) {
        final Object v = args.get( key );
        if ( v == null ) {
            throw new WorkValidationException( key, "is required." );
        }
        if ( v instanceof Number n && n.doubleValue() == Math.rint( n.doubleValue() ) ) {
            return n.longValue();
        }
        throw new WorkValidationException( key, "must be a whole number." );
    }

    /** A required string argument; missing or blank is a validation error by name. */
    static String requiredString( final Map< String, Object > args, final String key ) {
        final String v = stringArg( args, key );
        if ( v == null || v.isBlank() ) {
            throw new WorkValidationException( key, "is required." );
        }
        return v;
    }

    /** A required integer argument; missing is a validation error by name. */
    static int requiredInt( final Map< String, Object > args, final String key ) {
        final Integer v = intArg( args, key );
        if ( v == null ) {
            throw new WorkValidationException( key, "is required." );
        }
        return v;
    }

    /**
     * Refuses, by name and before anything else happens, any argument that is not in {@code allowed}: those in
     * {@code reasons} with their own text, the rest as unknown. An agent must never believe it changed something it
     * did not.
     */
    static void rejectOthers( final Map< String, Object > args, final Set< String > allowed,
                              final Map< String, String > reasons ) {
        for ( final String name : args.keySet() ) {
            if ( !allowed.contains( name ) ) {
                throw new WorkValidationException( name, reasons.getOrDefault( name, "Unknown argument." ) );
            }
        }
    }

    /** A property of a tool's input schema. */
    static Map< String, Object > prop( final String type, final String description, final Object example ) {
        final Map< String, Object > p = new LinkedHashMap<>();
        p.put( "type", type );
        p.put( "description", description );
        p.put( "examples", List.of( example ) );
        return p;
    }

    /** An object input schema. */
    static McpSchema.JsonSchema schema( final Map< String, Object > props, final List< String > required ) {
        return new McpSchema.JsonSchema( "object", props, required, null, null, null );
    }
}

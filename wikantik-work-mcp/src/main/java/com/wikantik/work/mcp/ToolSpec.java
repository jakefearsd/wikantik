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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A work tool declared once: its name, whether it writes, its description, and its arguments. The advertised input
 * schema, its required list, the annotations and the set of arguments the tool accepts are all derived from the
 * declaration, so the three can never drift apart. Arguments keep the order they are declared in.
 */
final class ToolSpec {

    /** Annotations of a tool that only reads. */
    private static final McpSchema.ToolAnnotations READ_ONLY =
        new McpSchema.ToolAnnotations( null, true, false, true, null, null );

    /** Annotations of a tool that changes something (none is destructive: history is append-only). */
    private static final McpSchema.ToolAnnotations WRITES =
        new McpSchema.ToolAnnotations( null, false, false, false, null, null );

    private final String name;
    private final boolean writes;
    private String description;
    private final Map< String, Object > properties = new LinkedHashMap<>();
    private final List< String > required = new ArrayList<>();

    private ToolSpec( final String name, final boolean writes ) {
        this.name = name;
        this.writes = writes;
    }

    /** A tool that only reads. */
    static ToolSpec reads( final String name ) {
        return new ToolSpec( name, false );
    }

    /** A tool that changes something. */
    static ToolSpec writes( final String name ) {
        return new ToolSpec( name, true );
    }

    ToolSpec description( final String text ) {
        this.description = text;
        return this;
    }

    /** An optional argument of a simple type. */
    ToolSpec arg( final String argName, final String type, final String argDescription, final Object example ) {
        return arg( argName, WorkMcpArgs.prop( type, argDescription, example ), false );
    }

    /** A required argument of a simple type. */
    ToolSpec requiredArg( final String argName, final String type, final String argDescription,
                          final Object example ) {
        return arg( argName, WorkMcpArgs.prop( type, argDescription, example ), true );
    }

    /** An argument whose schema is not the plain type/description/example shape. */
    ToolSpec arg( final String argName, final Map< String, Object > schema, final boolean isRequired ) {
        properties.put( argName, schema );
        if ( isRequired ) {
            required.add( argName );
        }
        return this;
    }

    String name() {
        return name;
    }

    boolean writes() {
        return writes;
    }

    /** The arguments the tool accepts: exactly the properties of its schema. */
    Set< String > allowed() {
        return Set.copyOf( properties.keySet() );
    }

    McpSchema.Tool definition() {
        return McpSchema.Tool.builder()
            .name( name )
            .description( description )
            .inputSchema( WorkMcpArgs.schema( properties, List.copyOf( required ) ) )
            .annotations( writes ? WRITES : READ_ONLY )
            .build();
    }
}

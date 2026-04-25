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
package com.wikantik.api.agent;

/**
 * A pointer at an MCP tool an agent should consider when working with this
 * page. {@code tool} is the canonical tool path
 * ({@code /knowledge-mcp/search_knowledge} or a bare tool name);
 * {@code when} is a one-line trigger description.
 */
public record McpToolHint( String tool, String when ) {
    public McpToolHint {
        if ( tool == null || tool.isBlank() ) {
            throw new IllegalArgumentException( "McpToolHint.tool required" );
        }
        if ( when == null || when.isBlank() ) {
            throw new IllegalArgumentException( "McpToolHint.when required" );
        }
    }
}

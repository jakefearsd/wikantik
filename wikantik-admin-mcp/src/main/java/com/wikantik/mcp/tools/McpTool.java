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

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * Common interface for all MCP tool implementations.
 *
 * <p>Each tool provides a schema definition and an execute method.
 * Tools that need author/user resolution from the MCP exchange should
 * also implement a {@code setDefaultAuthor} or {@code setDefaultUser} method.</p>
 */
public interface McpTool {

    /** The tool name used in the MCP protocol (e.g. "read_page"). */
    String name();

    /** Returns the MCP tool schema definition including parameters and annotations. */
    McpSchema.Tool definition();

    /** Executes the tool with the given arguments. */
    McpSchema.CallToolResult execute( Map< String, Object > arguments );
}

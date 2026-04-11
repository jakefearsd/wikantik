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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Audit logger for MCP tool write operations.
 *
 * <p>MCP tools currently run as a superuser gated only by {@code McpAccessFilter}
 * (see the security audit — per-user authorization inside tools is not implemented).
 * To keep forensic visibility, every state-changing MCP call emits a structured line
 * to the {@code SecurityLog} log4j logger via this helper.</p>
 *
 * <p>The log format is a single line of space-separated {@code key=value} pairs so
 * it can be grepped, parsed by log shippers, and correlated with access-filter
 * rejections (which also use {@code SecurityLog}).</p>
 */
public final class McpAudit {

    private static final Logger SECURITY = LogManager.getLogger( "SecurityLog" );

    private McpAudit() {
        // utility
    }

    /**
     * Records a state-changing MCP tool invocation.
     *
     * @param tool   the tool name (e.g. {@code import_content}, {@code rename_page})
     * @param action what the tool did (e.g. {@code created}, {@code updated}, {@code deleted}, {@code renamed})
     * @param target the page name or resource identifier affected
     * @param author the effective author recorded for the write, or {@code null} if unknown
     */
    public static void logWrite( final String tool, final String action, final String target, final String author ) {
        SECURITY.info( "MCP_WRITE tool={} action={} target={} author={}",
                safe( tool ), safe( action ), safe( target ), author == null || author.isBlank() ? "unknown" : author );
    }

    private static String safe( final String s ) {
        return s == null ? "unknown" : s;
    }
}

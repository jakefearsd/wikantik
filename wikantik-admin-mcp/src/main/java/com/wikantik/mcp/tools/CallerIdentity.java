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

import java.util.function.Supplier;

/**
 * The MCP client name of the tool call running on this thread, scoped to that one call.
 *
 * <p>{@link DefaultAuthorTool} keeps the client name in a field of the singleton tool, so two clients calling
 * concurrently can be credited with each other's writes. Tools that must never mis-attribute (those storing an
 * {@code agent:<name>} comment) read it from here instead: {@code McpServerInitializer} binds the name around the
 * call and always clears it afterwards, so a call from a client that sent no name sees {@code null}, never the
 * previous caller's name.</p>
 */
public final class CallerIdentity {

    private static final ThreadLocal< String > CLIENT_NAME = new ThreadLocal<>();

    private CallerIdentity() {
    }

    /** Runs {@code call} with {@code clientName} (null or blank means none) bound to this thread, then restores. */
    public static < T > T runAs( final String clientName, final Supplier< T > call ) {
        final String previous = CLIENT_NAME.get();
        if ( clientName == null || clientName.isBlank() ) {
            CLIENT_NAME.remove();
        } else {
            CLIENT_NAME.set( clientName );
        }
        try {
            return call.get();
        } finally {
            if ( previous == null ) {
                CLIENT_NAME.remove();
            } else {
                CLIENT_NAME.set( previous );
            }
        }
    }

    /** The client name bound to the current call, or null when the client sent none. */
    public static String clientName() {
        return CLIENT_NAME.get();
    }
}

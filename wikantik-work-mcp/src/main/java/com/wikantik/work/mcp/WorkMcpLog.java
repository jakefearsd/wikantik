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

import com.wikantik.mcp.tools.McpAudit;
import com.wikantik.work.Actor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * What the work endpoint writes to the security log: the success audit of a write (through {@link McpAudit}) and one
 * line for a refused write. Text that came from the caller or an admin is stripped of control characters and capped,
 * so it can never split or forge a line; no exception message, item text, comment body, token or login goes in.
 */
final class WorkMcpLog {

    private static final Logger SECURITY = LogManager.getLogger( "SecurityLog" );
    private static final int MAX_FIELD = 64;
    private static final String NONE = "-";

    private WorkMcpLog() {
    }

    /** {@code text} with every control, line-separator and format character removed, at most 64 characters; "-" if empty. */
    static String clean( final String text ) {
        if ( text == null ) {
            return NONE;
        }
        final StringBuilder b = new StringBuilder();
        text.codePoints().filter( cp -> !Character.isISOControl( cp ) && Character.getType( cp ) != Character.LINE_SEPARATOR
            && Character.getType( cp ) != Character.PARAGRAPH_SEPARATOR && Character.getType( cp ) != Character.FORMAT )
            .limit( MAX_FIELD ).forEach( b::appendCodePoint );
        return b.isEmpty() ? NONE : b.toString();
    }

    /** The success audit of a write, authored by the agent's name (a person's login never appears: callers are agents or keys' owners). */
    static void written( final String tool, final String action, final String itemKey, final Actor actor ) {
        McpAudit.logWrite( tool, action, itemKey, clean( actor.login() ) );
    }

    /** One INFO line for a write that was refused; {@code kind} is the refusal's prefix, never its message. */
    static void refused( final String tool, final Object itemKeyArgument, final Actor actor, final String kind ) {
        SECURITY.info( "MCP_WORK_REFUSED tool={} item={} agent={} kind={}", clean( tool ),
            itemKeyArgument instanceof String s ? clean( s ) : NONE, actor == null ? NONE : clean( actor.login() ),
            clean( kind ) );
    }
}

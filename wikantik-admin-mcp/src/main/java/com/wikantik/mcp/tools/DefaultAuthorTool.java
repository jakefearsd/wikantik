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

/**
 * Base class for write-side MCP tools that attribute saves to the calling
 * agent. Centralises the {@link AuthorConfigurable#setDefaultAuthor} contract
 * and the null/blank guard so subclasses can focus on tool behaviour.
 *
 * <p>The initial default of {@code "mcp-agent"} is used until an MCP client
 * {@code initialize} handshake supplies its {@code clientInfo.name}, which
 * {@code McpServerInitializer} then forwards to every
 * {@link AuthorConfigurable} tool.
 */
public abstract class DefaultAuthorTool implements AuthorConfigurable {

    protected String defaultAuthor = "mcp-agent";

    @Override
    public final void setDefaultAuthor( final String author ) {
        if ( author != null && !author.isBlank() ) {
            this.defaultAuthor = author;
        }
    }
}

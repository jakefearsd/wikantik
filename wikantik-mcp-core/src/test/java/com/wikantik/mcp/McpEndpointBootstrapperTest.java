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

import com.wikantik.api.core.Engine;
import com.wikantik.auth.apikeys.ApiKeyService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Cheap unit coverage for {@link McpEndpointBootstrapper.Builder} validation.
 *
 * <p>{@code registerAccessFilter}/{@code registerTransport} need a live servlet
 * container + wired {@link Engine} subsystem and are intentionally left to the
 * wikantik-admin-mcp / wikantik-knowledge integration surfaces that exercise the
 * real startup sequence; this class covers only the builder's own required-field
 * validation, which is pure and cheap.</p>
 */
class McpEndpointBootstrapperTest {

    private McpEndpointBootstrapper.Builder validBuilder() {
        return McpEndpointBootstrapper.builder()
                .logTag( "Test" )
                .endpointPath( "/test-mcp" )
                .filterName( "testFilter" )
                .servletName( "testServlet" )
                .engine( mock( Engine.class ) );
    }

    @Test
    void buildSucceedsWithAllRequiredFieldsSet() {
        assertNotNull( validBuilder().build() );
    }

    @Test
    void buildFailsWhenLogTagMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .endpointPath( "/test-mcp" )
                        .filterName( "testFilter" )
                        .servletName( "testServlet" )
                        .engine( mock( Engine.class ) )
                        .build() );
    }

    @Test
    void buildFailsWhenEndpointPathMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .logTag( "Test" )
                        .filterName( "testFilter" )
                        .servletName( "testServlet" )
                        .engine( mock( Engine.class ) )
                        .build() );
    }

    @Test
    void buildFailsWhenFilterNameMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .logTag( "Test" )
                        .endpointPath( "/test-mcp" )
                        .servletName( "testServlet" )
                        .engine( mock( Engine.class ) )
                        .build() );
    }

    @Test
    void buildFailsWhenServletNameMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .logTag( "Test" )
                        .endpointPath( "/test-mcp" )
                        .filterName( "testFilter" )
                        .engine( mock( Engine.class ) )
                        .build() );
    }

    @Test
    void buildFailsWhenEngineMissing() {
        assertThrows( IllegalStateException.class, () ->
                McpEndpointBootstrapper.builder()
                        .logTag( "Test" )
                        .endpointPath( "/test-mcp" )
                        .filterName( "testFilter" )
                        .servletName( "testServlet" )
                        .build() );
    }

    @Test
    void loadOnStartupAndRequiredScopeSettersAreFluent() {
        final McpEndpointBootstrapper bootstrapper = validBuilder()
                .loadOnStartup( 5 )
                .requiredScope( ApiKeyService.Scope.MCP_READ )
                .build();
        assertNotNull( bootstrapper );
    }
}

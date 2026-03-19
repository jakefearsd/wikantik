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
package org.apache.wiki.its.mcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Base class for MCP integration tests. Creates and closes an {@link McpTestClient}
 * for the test class lifecycle. Does not depend on Selenide or any browser.
 */
@DisabledOnOs( OS.WINDOWS )
public class WithMcpTestSetup {

    protected static McpTestClient mcp;

    @BeforeAll
    public static void setUpMcpClient() {
        mcp = McpTestClient.create();
    }

    @AfterAll
    public static void tearDownMcpClient() {
        if ( mcp != null ) {
            mcp.close();
            mcp = null;
        }
    }

    protected static String uniquePageName( final String prefix ) {
        return prefix + "_" + System.currentTimeMillis() + "_" + Thread.currentThread().threadId();
    }
}

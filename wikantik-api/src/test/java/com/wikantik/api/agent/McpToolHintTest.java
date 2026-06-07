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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class McpToolHintTest {

    @Test
    void retainsToolAndWhen() {
        final McpToolHint h = new McpToolHint( "/knowledge-mcp/search_knowledge", "when looking for related entities" );
        assertEquals( "/knowledge-mcp/search_knowledge", h.tool() );
        assertEquals( "when looking for related entities", h.when() );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource( strings = { "   " } )
    void rejectsBlankTool( final String bad ) {
        assertThrows( IllegalArgumentException.class, () -> new McpToolHint( bad, "trigger" ) );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource( strings = { "   " } )
    void rejectsBlankWhen( final String bad ) {
        assertThrows( IllegalArgumentException.class, () -> new McpToolHint( "tool", bad ) );
    }
}

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentHintsBlockTest {

    @Test
    void emptyFactoryReturnsEmptyImmutableLists() {
        final AgentHintsBlock empty = AgentHintsBlock.empty();
        assertNotNull( empty.prefer_tools() );
        assertNotNull( empty.prefer_pages() );
        assertTrue( empty.prefer_tools().isEmpty() );
        assertTrue( empty.prefer_pages().isEmpty() );
        assertThrows( UnsupportedOperationException.class,
                () -> empty.prefer_tools().add( "x" ) );
    }

    @Test
    void nullArgsBecomeEmptyLists() {
        final AgentHintsBlock b = new AgentHintsBlock( null, null );
        assertEquals( List.of(), b.prefer_tools() );
        assertEquals( List.of(), b.prefer_pages() );
    }

    @Test
    void preferredPageRequiresCanonicalIdAndTitle() {
        assertThrows( IllegalArgumentException.class,
                () -> new PreferredPage( null, "Title", "cluster_member" ) );
        assertThrows( IllegalArgumentException.class,
                () -> new PreferredPage( "id", null, "cluster_member" ) );
    }

    @Test
    void preferredPageDefaultsRoleToClusterMember() {
        final PreferredPage p = new PreferredPage( "id", "Title", null );
        assertEquals( "cluster_member", p.role() );
    }
}

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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class ListClaimableToolTest {

    private WorkMcpFixture f;
    private ListClaimableTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        f = new WorkMcpFixture();
        tool = new ListClaimableTool( f.svc, f.directory() );
    }

    @Test
    void limitClampedTo50() throws SQLException {
        for ( int i = 0; i < 55; i++ ) {
            f.ready( "PLAT", "task " + i );
        }

        assertEquals( 50, WorkMcpFixture.json( tool.call( f.builderActor, Map.of( "limit", 500 ) ) )
            .getAsJsonArray( "items" ).size() );
        assertEquals( 20, WorkMcpFixture.json( tool.call( f.builderActor, Map.of() ) ).getAsJsonArray( "items" ).size(),
            "the default is 20" );
        assertEquals( 3, WorkMcpFixture.json( tool.call( f.builderActor, Map.of( "limit", 3.0 ) ) )
            .getAsJsonArray( "items" ).size(), "a JSON number may arrive as a double" );
    }

    @Test
    void argumentsOfTheWrongTypeAreRejectedByName() {
        assertEquals( "project: must be a string.", WorkMcpFixture.text( tool.call( f.builderActor, Map.of( "project", 7 ) ) ) );
        assertEquals( "queue: must be a string.", WorkMcpFixture.text( tool.call( f.builderActor, Map.of( "queue", true ) ) ) );
        assertEquals( "limit: must be a number.", WorkMcpFixture.text( tool.call( f.builderActor, Map.of( "limit", "ten" ) ) ) );
    }

    @Test
    void unknownProjectIsNotFound() {
        assertEquals( "Not found.", WorkMcpFixture.text( tool.call( f.builderActor, Map.of( "project", "NOPE" ) ) ) );
    }

    @Test
    void aProjectTheAgentIsNotAdmittedToIsTheSameNotFound() throws SQLException {
        f.ready( "SECRET", "hidden" );
        assertEquals( tool.call( f.builderActor, Map.of( "project", "NOPE" ) ),
            tool.call( f.builderActor, Map.of( "project", "SECRET" ) ) );
    }

    @Test
    void itemsCarryTheirShape() throws SQLException {
        final Item item = f.ready( "PLAT", "Shape" );
        f.ready( "SECRET", "invisible to the agent" );

        final JsonArray items = WorkMcpFixture.json( tool.call( f.builderActor, Map.of( "queue", "implementer" ) ) )
            .getAsJsonArray( "items" );

        assertEquals( 1, items.size() );
        final JsonObject o = items.get( 0 ).getAsJsonObject();
        assertEquals( item.key(), o.get( "key" ).getAsString() );
        assertEquals( "Shape", o.get( "title" ).getAsString() );
        assertEquals( "task", o.get( "level" ).getAsString() );
        assertEquals( "software", o.get( "kind" ).getAsString() );
        assertEquals( "ready", o.get( "status" ).getAsString() );
        assertEquals( "Implementer", o.get( "queue" ).getAsString() );
        assertEquals( "implementer", o.get( "queueKey" ).getAsString() );
        assertTrue( o.has( "priority" ) && o.has( "productKey" ) && o.has( "parentKey" ) && o.has( "dueOn" ) );
    }

    @Test
    void anotherQueueYieldsNothing() throws SQLException {
        f.ready( "PLAT", "Shape" );
        assertEquals( 0, WorkMcpFixture.json( tool.call( f.builderActor, Map.of( "queue", "reviewer" ) ) )
            .getAsJsonArray( "items" ).size() );
    }
}

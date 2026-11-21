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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.work.Actor;
import com.wikantik.work.Agent;
import com.wikantik.work.AgentDraft;
import com.wikantik.work.Item;
import com.wikantik.work.ItemDraft;
import com.wikantik.work.ItemPatch;
import com.wikantik.work.JdbcWorkStore;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkSettings;
import io.modelcontextprotocol.spec.McpSchema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A real {@link WorkService} over the migrated Postgres, with an agent {@code builder} (queue {@code implementer},
 * member of project {@code PLAT} only), a person {@code lena} (login {@code lena}, display name "Lena Lead", lead of
 * both {@code PLAT} and {@code SECRET}) and a project {@code SECRET} the agent was never admitted to.
 */
final class WorkMcpFixture {

    static final String[] TABLES = { "work_agent_credential", "work_agent_sponsor_history", "work_agent_queue", "work_agent", "work_queue",
        "work_link", "work_mention", "work_comment", "work_event", "work_item_revision", "work_item", "work_member",
        "work_product", "work_project" };

    static final Gson GSON = new Gson();

    final DataSource ds;
    /** When true the directory fails every sponsor lookup, as an outage would. */
    boolean directoryDown;
    final WorkService svc;
    final Map< String, String > displayNames = new HashMap<>();
    final Actor admin = Actor.human( "admin", Set.of(), true );
    final Actor lena = Actor.human( "lena", Set.of(), false );
    final Agent builder;
    final Actor builderActor;
    int seq;

    WorkMcpFixture() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( TABLES );
        displayNames.put( "lena", "Lena Lead" );
        displayNames.put( "jdoe", "Jane Doe" );
        svc = new WorkService( new JdbcWorkStore( ds ), md -> "<p>" + md + "</p>", directory(),
            Clock.systemUTC(), new WorkSettings( Duration.ofMinutes( 30 ) ), null );
        for ( final String project : List.of( "PLAT", "SECRET" ) ) {
            svc.createProject( admin, project, project + " project", "" );
            svc.putMember( admin, project, "lena", "user", WorkRole.LEAD );
        }
        svc.registry().createQueue( admin, "implementer", "Implementer", "" );
        svc.registry().createQueue( admin, "reviewer", "Reviewer", "" );
        builder = svc.registry().registerAgent( admin, new AgentDraft( "builder", "Acme", "claude-code",
            List.of( "implementer" ) ) ).agent();
        builderActor = Actor.agent( builder.id(), builder.name(), com.wikantik.work.Actor.human( "admin", java.util.Set.of(), true ) );
        svc.putMember( admin, "PLAT", "builder", "agent", WorkRole.MEMBER );
    }

    WorkDirectory directory() {
        return new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public Optional< Person > person( final String login ) {
                if ( directoryDown ) {
                    throw new com.wikantik.work.WorkUnavailableException( "test: directory down" );
                }
                return Optional.of( new Person( login, Set.of(), "admin".equals( login ) ) );
            }
            @Override public String displayName( final String login ) {
                return displayNames.getOrDefault( login, "Someone" );
            }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return displayNames.entrySet().stream().filter( e -> e.getValue().equals( name ) )
                    .map( Map.Entry::getKey ).findFirst();
            }
        };
    }

    /** A task in the project, in the implementer queue, moved to ready. */
    Item ready( final String project, final String title ) throws SQLException {
        final Item draft = svc.createItem( lena, new ItemDraft( project, WorkLevel.TASK, null, null,
            WorkKind.SOFTWARE, title, "", null, null, null, "implementer", null, null ) ).item();
        return svc.updateItem( lena, draft.key(), new ItemPatch( draft.version(), Map.of( "status", "ready" ), null ) )
            .item();
    }

    /** The result as the agent reads it: the first text content. */
    static String text( final McpSchema.CallToolResult r ) {
        return ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
    }

    static JsonObject json( final McpSchema.CallToolResult r ) {
        return JsonParser.parseString( text( r ) ).getAsJsonObject();
    }

    /** An api key record for the key id, as the access filter hands it to the request. */
    static ApiKeyService.Record record( final int keyId, final String owner, final ApiKeyService.Scope scope ) {
        return new ApiKeyService.Record( keyId, "hash-" + keyId, owner, "label", scope, Instant.now(), "admin", null,
            null, null, false );
    }

    long scalar( final String sql ) throws SQLException {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery( sql ) ) {
            rs.next();
            return rs.getLong( 1 );
        }
    }

    long apiKey( final String scope ) throws SQLException {
        return scalar( "INSERT INTO api_keys (key_hash, principal_login, scope) VALUES ('"
            + UUID.randomUUID().toString().replace( "-", "" ) + "', 'lena', '" + scope + "') RETURNING id" );
    }
}

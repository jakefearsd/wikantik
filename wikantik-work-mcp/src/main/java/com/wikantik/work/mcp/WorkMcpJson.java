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

import com.wikantik.work.Actor;
import com.wikantik.work.Agent;
import com.wikantik.work.CommentView;
import com.wikantik.work.Item;
import com.wikantik.work.Link;
import com.wikantik.work.Queue;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkEvent;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkService.ItemSummary;
import com.wikantik.work.WorkService.ItemView;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an agent reads: people by display name, agents and queues by name. Never a login, never an API key id, never
 * anyone's token. Text is the Markdown source, never rendered HTML.
 */
final class WorkMcpJson {

    private static final Logger LOG = LogManager.getLogger( WorkMcpJson.class );

    /** How many of an item's most recent events {@code get_item} shows. */
    static final int EVENTS_SHOWN = 20;

    private static final String UNKNOWN_AGENT = "an agent";
    private static final String QUEUE_PREFIX = "queue:";
    private static final String AGENT_PREFIX = "agent:";

    private final WorkDirectory directory;
    private final Map< Long, String > agents;
    private final Map< String, String > queues;

    WorkMcpJson( final WorkDirectory directory, final Map< Long, String > agents, final Map< String, String > queues ) {
        this.directory = directory;
        this.agents = agents;
        this.queues = queues;
    }

    /** The names the actor may see in the registry (agents by id, queues by key). */
    static WorkMcpJson forActor( final WorkService svc, final WorkDirectory directory, final Actor actor )
            throws SQLException {
        final Map< Long, String > agents = new HashMap<>();
        for ( final Agent a : svc.registry().listAgents( actor ) ) {
            agents.put( a.id(), a.name() );
        }
        final Map< String, String > queues = new HashMap<>();
        for ( final Queue q : svc.registry().listQueues( actor ) ) {
            queues.put( q.key(), q.name() );
        }
        return new WorkMcpJson( directory, agents, queues );
    }

    /** An item as {@code get_item} shows it, read fresh (its events included) for the actor. */
    static Map< String, Object > fullItem( final WorkService svc, final WorkDirectory directory, final Actor actor,
                                           final ItemView view ) throws SQLException {
        final WorkMcpJson names = forActor( svc, directory, actor );
        final Map< String, Object > o = names.item( view, svc.events( actor, view.item().key() ) );
        o.put( "effective", WorkMcpChanges.effective( svc.policyOf( actor, view.item().key() ).effective() ) );
        o.put( "changes", WorkMcpChanges.list( names, svc.changes().views( actor, view.item().key() ) ) );
        o.put( "shipped_in", WorkMcpDeployments.briefs( svc.deployments().shippedIn( actor, view.item().key() ) ) );
        return o;
    }

    String person( final String login ) {
        return login == null ? null : directory.displayName( login );
    }

    String agent( final Long id, final String stored ) {
        final String name = id == null ? null : agents.get( id );
        return name != null ? name : stored != null ? stored : UNKNOWN_AGENT;
    }

    /** The queue's name; its key when the registry does not show it. */
    String queue( final String key ) {
        return key == null ? null : queues.getOrDefault( key, key );
    }

    Map< String, Object > summary( final ItemSummary s ) {
        final Item i = s.item();
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "key", i.key() );
        o.put( "title", i.title() );
        o.put( "level", i.level().wire() );
        o.put( "kind", i.kind().wire() );
        o.put( "type", i.type().wire() );
        o.put( "labels", i.labels() );
        o.put( "status", i.status().wire() );
        o.put( "priority", i.priority() );
        o.put( "queue", queue( i.queue() ) );
        o.put( "queueKey", i.queue() );
        o.put( "productKey", s.productKey() );
        o.put( "parentKey", s.parentKey() );
        o.put( "dueOn", i.dueOn() == null ? null : i.dueOn().toString() );
        return o;
    }

    Map< String, Object > item( final ItemView view, final List< WorkEvent > events ) {
        final Item i = view.item();
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "key", i.key() );
        o.put( "title", i.title() );
        o.put( "body", i.body() == null ? "" : i.body() );
        o.put( "level", i.level().wire() );
        o.put( "kind", i.kind().wire() );
        o.put( "type", i.type().wire() );
        o.put( "labels", i.labels() );
        o.put( "status", i.status().wire() );
        o.put( "priority", i.priority() );
        o.put( "version", i.version() );
        o.put( "dueOn", i.dueOn() == null ? null : i.dueOn().toString() );
        o.put( "assignee", person( i.assignee() ) );
        o.put( "queue", queue( i.queue() ) );
        o.put( "queueKey", i.queue() );
        o.put( "claim", claim( i ) );
        o.put( "parentKey", view.parentKey() );
        o.put( "productKey", view.productKey() );
        o.put( "wikiCluster", view.wikiCluster() );
        o.put( "blocked", view.blocked() );
        o.put( "links", view.links().stream().map( WorkMcpJson::link ).toList() );
        o.put( "comments", view.comments().stream().filter( c -> c.removedAt() == null ).map( this::comment ).toList() );
        final int from = Math.max( 0, events.size() - EVENTS_SHOWN );
        o.put( "events", events.subList( from, events.size() ).stream().map( this::event ).toList() );
        return o;
    }

    private Map< String, Object > claim( final Item i ) {
        if ( i.claimedByAgent() == null ) {
            return null;
        }
        final Map< String, Object > c = new LinkedHashMap<>();
        c.put( "agent", agent( i.claimedByAgent(), null ) );
        c.put( "expiresAt", i.claimExpiresAt() == null ? null : i.claimExpiresAt().toString() );
        return c;
    }

    private static Map< String, Object > link( final Link l ) {
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "kind", l.kind() );
        o.put( "target", l.target() );
        return o;
    }

    private Map< String, Object > comment( final CommentView c ) {
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "author", c.authorAgentId() != null ? agent( c.authorAgentId(), c.author() ) : person( c.author() ) );
        o.put( "authorKind", c.authorAgentId() != null ? "agent" : "human" );
        o.put( "body", c.body() );
        o.put( "at", c.createdAt() == null ? null : c.createdAt().toString() );
        return o;
    }

    Map< String, Object > event( final WorkEvent e ) {
        final boolean byAgent = e.actorKind() == Actor.Kind.AGENT;
        final Map< String, Object > o = new LinkedHashMap<>();
        o.put( "type", e.type() );
        o.put( "actor", byAgent ? agent( e.agentId(), e.actor() ) : person( e.actor() ) );
        o.put( "actorKind", byAgent ? "agent" : "human" );
        o.put( "from", eventValue( e.type(), e.fromValue() ) );
        o.put( "to", eventValue( e.type(), e.toValue() ) );
        o.put( "note", e.note() );
        o.put( "at", e.at() == null ? null : e.at().toString() );
        return o;
    }

    /**
     * An event value for a reader: statuses and field values as stored; a person by display name, a
     * {@code queue:<key>} by the queue's name and an {@code agent:<id>} by the agent's name. A hand-off value is
     * {@code status|target}.
     */
    private String eventValue( final String type, final String value ) {
        if ( value == null || value.isEmpty() ) {
            return value;
        }
        return switch ( type ) {
            case WorkEvent.ASSIGNED -> target( value );
            case WorkEvent.HANDOFF -> {
                final int bar = value.indexOf( '|' );
                yield bar < 0 ? value : value.substring( 0, bar + 1 ) + target( value.substring( bar + 1 ) );
            }
            case WorkEvent.RELEASED -> value.startsWith( AGENT_PREFIX ) ? agentFromText( value.substring( AGENT_PREFIX.length() ) ) : value;
            default -> value;
        };
    }

    /** A person's login, or a {@code queue:<key>}, as the name a reader knows it by. */
    private String target( final String text ) {
        if ( text.isEmpty() ) {
            return text;
        }
        return text.startsWith( QUEUE_PREFIX ) ? queue( text.substring( QUEUE_PREFIX.length() ) ) : person( text );
    }

    private String agentFromText( final String idText ) {
        try {
            return agent( Long.parseLong( idText.trim() ), null );
        } catch ( final NumberFormatException e ) {
            LOG.warn( "An event named an agent by '{}', which is not an id; showing it as unknown", idText );
            return UNKNOWN_AGENT;
        }
    }
}

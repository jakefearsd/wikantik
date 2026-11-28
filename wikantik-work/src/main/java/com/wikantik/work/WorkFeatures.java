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
package com.wikantik.work;

import com.wikantik.work.WorkScopes.Scope;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

/**
 * Reads that go beyond the core item calls, reached through {@link WorkService#features()}. Like every project read,
 * an unseen project is "not found".
 */
public final class WorkFeatures {

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkSprintsService sprints;
    private final WorkNotificationsService notifications;
    private final WorkWebhooksService webhooks;
    private final WorkFindService find;
    private final WorkAttachmentsService attachments;
    private final WorkCodeHostService codeHost;
    private final WorkFlowService flow;
    private final WorkIntakeService intake;
    private final WorkDirectory directory;

    WorkFeatures( final WorkStore store, final WorkScopes scopes, final ItemViews views, final Clock clock,
                  final FeatureDeps deps ) {
        this.store = store;
        this.scopes = scopes;
        this.directory = deps.directory();
        this.sprints = new WorkSprintsService( store, scopes, views, clock );
        this.notifications = new WorkNotificationsService( store, scopes, deps.people(), clock, deps.listener(),
            deps.ports() );
        this.webhooks = new WorkWebhooksService( store, scopes, deps.listener(), deps.audit(), deps.ports(), clock );
        this.find = new WorkFindService( store, scopes, deps.people(), views );
        this.attachments = new WorkAttachmentsService( store, scopes, deps.settings() );
        this.codeHost = new WorkCodeHostService( store, scopes, clock, deps );
        this.flow = new WorkFlowService( store, scopes, views, clock );
        this.intake = new WorkIntakeService( store, scopes, views, deps.people(), deps.applier(), deps.settings() );
    }

    /** Notifications: the inbox, watching, the email preference and the dispatch. */
    public WorkNotificationsService notifications() {
        return notifications;
    }

    /** Webhooks: a project's outbound event feeds, managed by its leads. */
    public WorkWebhooksService webhooks() {
        return webhooks;
    }

    /** Find: the cross-project item search and the saved filters. */
    public WorkFindService find() {
        return find;
    }

    /** Sprints: the sprint service. */
    public WorkSprintsService sprints() {
        return sprints;
    }

    /** The labels in use in a project, most used first, at most 200; cancelled items do not count. */
    public List< LabelCount > labelsOf( final Actor actor, final String projectKey ) throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.VIEW, null );
        return store.labelCounts( s.project().id() );
    }

    /**
     * Sets or clears (blank {@code productKey}) the product that new stories and tasks of the project start in.
     * A project lead's call; the product must exist and not be archived.
     */
    public void setDefaultProduct( final Actor actor, final String projectKey, final String productKey )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.MANAGE_PROJECT, null );
        s.requireWritable();
        Product product = null;
        if ( productKey != null && !productKey.isBlank() ) {
            product = store.product( productKey.trim() ).filter( p -> p.archivedAt() == null ).orElse( null );
            if ( product == null ) {
                throw new WorkValidationException( "defaultProductKey", "No such product: " + productKey.trim() );
            }
        }
        store.notifyStores().defaults().setDefaultProduct( s.project().id(), product, actor );
    }

    /** The most group names a suggestion call returns. */
    private static final int MAX_GROUP_SUGGESTIONS = 10;

    /**
     * Group names containing the query (any letter case), those that start with it first, then alphabetical, at most
     * ten; a blank query suggests nothing. A project lead's call, for filling in a group member.
     */
    public List< String > groupSuggestions( final Actor actor, final String projectKey, final String query )
            throws SQLException {
        final Scope s = scopes.scope( actor, projectKey );
        s.require( Action.MANAGE_PROJECT, null );
        if ( query == null || query.isBlank() ) {
            return List.of();
        }
        final String needle = query.trim().toLowerCase( Locale.ROOT );
        return directory.groupNames().stream()
            .filter( name -> name.toLowerCase( Locale.ROOT ).contains( needle ) )
            .sorted( java.util.Comparator
                .comparing( ( String name ) -> !name.toLowerCase( Locale.ROOT ).startsWith( needle ) )
                .thenComparing( String.CASE_INSENSITIVE_ORDER ) )
            .limit( MAX_GROUP_SUGGESTIONS )
            .toList();
    }

    /** Attachments: files on an item (Part 7). */
    public WorkAttachmentsService attachments() {
        return attachments;
    }

    /** Flow and DORA figures, computed at read time (Part 6). */
    public WorkFlowService flow() {
        return flow;
    }

    /** Intake: filing into a project one is not a member of, and following what one filed (Part 8). */
    public WorkIntakeService intake() {
        return intake;
    }

    /** The code host: signed-hook check, enabling the hook, and applying what the host reports. */
    public WorkCodeHostService codeHost() {
        return codeHost;
    }
}

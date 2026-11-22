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

/**
 * Reads that go beyond the core item calls, reached through {@link WorkService#features()}. Like every project read,
 * an unseen project is "not found".
 */
public final class WorkFeatures {

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkSprintsService sprints;

    WorkFeatures( final WorkStore store, final WorkScopes scopes, final ItemViews views, final Clock clock ) {
        this.store = store;
        this.scopes = scopes;
        this.sprints = new WorkSprintsService( store, scopes, views, clock );
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
}

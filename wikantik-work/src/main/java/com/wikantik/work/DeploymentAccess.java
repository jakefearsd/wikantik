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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What one actor may see and do of one deployment. A deployment has no project of its own: it carries items, and
 * its visibility is theirs. This is the only place deployments meet standing: the actor's role is asked, through
 * {@link WorkStanding}, in every project an item of the deployment belongs to.
 */
final class DeploymentAccess {

    private final List< Item > visibleItems = new ArrayList<>();
    private boolean memberOfEvery = true;
    private boolean leadOfEvery = true;
    private boolean any;

    private DeploymentAccess() {
    }

    static DeploymentAccess of( final WorkStore store, final WorkStanding standing, final Actor actor,
                                final List< Long > itemIds ) throws SQLException {
        final DeploymentAccess access = new DeploymentAccess();
        final Map< Long, Optional< WorkRole > > roles = new HashMap<>();
        for ( final long itemId : itemIds ) {
            final Item item = store.itemById( itemId ).orElse( null );
            if ( item == null ) {
                continue;
            }
            access.any = true;
            Optional< WorkRole > role = roles.get( item.projectId() );
            if ( role == null ) {
                role = standing.roleIn( item.projectId(), actor );
                roles.put( item.projectId(), role );
            }
            if ( actor.instanceAdmin() || role.isPresent() ) {
                access.visibleItems.add( item );
            }
            access.memberOfEvery &= role.filter( r -> r.atLeast( WorkRole.MEMBER ) ).isPresent();
            access.leadOfEvery &= role.filter( r -> r == WorkRole.LEAD ).isPresent();
        }
        return access;
    }

    /** The carried items the actor can see, in the order the deployment lists them. */
    List< Item > visibleItems() {
        return visibleItems;
    }

    /** Whether the actor can see the deployment at all: one visible item is enough. */
    boolean seesAny() {
        return !visibleItems.isEmpty();
    }

    /** A member (or lead) of every project the deployment carries an item from. */
    boolean memberOfEvery() {
        return any && memberOfEvery;
    }

    /** A lead of every project the deployment carries an item from. */
    boolean leadOfEvery() {
        return any && leadOfEvery;
    }
}

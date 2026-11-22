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
import java.time.Instant;

/** Items with controlled creation times and status moves at controlled times, written by SQL (the service uses NOW()). */
final class FlowFixtures {

    private FlowFixtures() {
    }

    /** A task in PLAT's implementer queue, created by lena, as if created at {@code createdAt}. */
    static Item item( final GateFixture f, final String title, final Instant createdAt ) throws SQLException {
        return item( f, "PLAT", WorkLevel.TASK, title, createdAt );
    }

    static Item item( final GateFixture f, final String project, final WorkLevel level, final String title,
                      final Instant createdAt ) throws SQLException {
        final Actor by = project.equals( "PLAT" ) ? f.lena : Actor.human( "olga", java.util.Set.of(), false );
        final Item made = f.svc.createItem( by, new ItemDraft( project, level, null,
            level == WorkLevel.EPIC ? null : "wikantik", WorkKind.SOFTWARE, title, "", null, null, null,
            level == WorkLevel.EPIC ? null : "implementer", null, null ) ).item();
        update( f, "UPDATE work_item SET created_at = '" + createdAt + "'::timestamptz WHERE id = "
            + made.id() );
        return made;
    }

    /** Records a status event at {@code at} and sets the item's status, as a real move would have. */
    static void move( final GateFixture f, final Item item, final String from, final String to, final Instant at )
            throws SQLException {
        update( f, "INSERT INTO work_event (project_id, item_id, at, actor, type, from_value, to_value) VALUES ("
            + item.projectId() + "," + item.id() + ",'" + at + "'::timestamptz,'lena','status','" + from
            + "','" + to + "')" );
        update( f, "UPDATE work_item SET status = '" + to + "' WHERE id = " + item.id() );
    }

    static void update( final GateFixture f, final String sql ) throws SQLException {
        WorkAgentFixtures.exec( f.ds, sql );
    }
}

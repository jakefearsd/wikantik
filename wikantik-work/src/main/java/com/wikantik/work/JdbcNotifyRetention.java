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

import com.wikantik.jdbc.Jdbc;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Retention of the derived notification rows: bounded delete batches, oldest first. Work records are never touched. */
final class JdbcNotifyRetention {

    private final Jdbc jdbc;

    JdbcNotifyRetention( final Jdbc jdbc ) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes notifications and skipped-event rows created before {@code before}, at most {@code batchSize} rows per
     * statement and {@code maxBatches} statements per table. {@code more} is true when either table used every batch
     * and each deleted a full batch, so the caller should come back soon.
     */
    WorkNotificationStore.Pruned prune( final Instant before, final int batchSize, final int maxBatches )
            throws SQLException {
        final WorkNotificationStore.Pruned notices = deleteOld( "work_notification", "created_at", before, batchSize,
            maxBatches );
        final WorkNotificationStore.Pruned skipped = deleteOld( "work_notify_skipped", "skipped_at", before, batchSize,
            maxBatches );
        return new WorkNotificationStore.Pruned( notices.deleted() + skipped.deleted(), notices.more() || skipped.more() );
    }

    private WorkNotificationStore.Pruned deleteOld( final String table, final String column, final Instant before,
                                                    final int batchSize, final int maxBatches ) throws SQLException {
        final String sql = "DELETE FROM " + table + " WHERE id IN ( SELECT id FROM " + table + " WHERE " + column
            + " < ? ORDER BY id LIMIT ? )";
        int total = 0;
        for ( int batch = 0; batch < maxBatches; batch++ ) {
            final int n = jdbc.update( sql, ps -> {
                ps.setTimestamp( 1, Timestamp.from( before ) );
                ps.setInt( 2, batchSize );
            } );
            total += n;
            if ( n < batchSize ) {
                return new WorkNotificationStore.Pruned( total, false );
            }
        }
        return new WorkNotificationStore.Pruned( total, true );
    }
}

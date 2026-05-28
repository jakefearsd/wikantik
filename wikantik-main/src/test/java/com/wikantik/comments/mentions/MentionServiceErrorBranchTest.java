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
package com.wikantik.comments.mentions;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Drives the SQLException catch path on every read-method in
 *  {@link MentionService} so the defensive returns (empty list / false / 0)
 *  are exercised. */
class MentionServiceErrorBranchTest {

    private static DataSource failingDataSource() throws SQLException {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "boom" ) );
        return ds;
    }

    private static MentionService svc() throws SQLException {
        return new MentionService( failingDataSource(), s -> true );
    }

    @Test
    void findByComment_returns_empty_list_on_sql_failure() throws SQLException {
        assertTrue( svc().findByComment( UUID.randomUUID() ).isEmpty() );
    }

    @Test
    void markRead_returns_false_on_sql_failure() throws SQLException {
        assertFalse( svc().markRead( UUID.randomUUID(), "alice" ) );
    }

    @Test
    void markAllRead_returns_zero_on_sql_failure() throws SQLException {
        assertEquals( 0, svc().markAllRead( "alice" ) );
    }

    @Test
    void unreadCount_returns_zero_on_sql_failure() throws SQLException {
        assertEquals( 0, svc().unreadCount( "alice" ) );
    }
}

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
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The notification outbox reader and the per-person inbox. Every inbox statement is filtered to the given projects. */
public interface WorkNotificationStore {

    /** Decides who is told about one event, given the facts the dispatcher read. */
    @FunctionalInterface
    interface NoticePlanner {
        Map< String, NoticeReason > plan( WorkEvent e, EventFacts facts ) throws SQLException;

        /**
         * Whether the login may be made a watcher of the event's item because of this event (an author, a
         * mentioned name). Asked before the watch row is written, so a name with no standing in the project, or an
         * agent, never leaves a stray row. The default allows everyone.
         */
        default boolean mayWatch( final WorkEvent e, final String login ) throws SQLException {
            return true;
        }
    }

    /**
     * Turns up to {@code maxEvents} new, committed events into notification rows and moves the cursor, all in one
     * transaction: a failure leaves rows and cursor untouched. The cursor is (transaction id, event id) and the window
     * ends at the snapshot's xmin, so an event whose transaction commits late is never skipped and never repeated.
     * The first ever call only plants the cursor at the current end of the log and returns 0.
     *
     * @return the number of events read
     */
    int dispatch( int maxEvents, NoticePlanner planner ) throws SQLException;

    /** Newest first; {@code beforeId} (exclusive) pages. Empty with no query when {@code projectIds} is empty. */
    List< NoticeView > inbox( String recipient, Set< Long > projectIds, Long beforeId, int limit ) throws SQLException;

    /** Unread rows in the given projects. */
    int unread( String recipient, Set< Long > projectIds ) throws SQLException;

    /** Marks the recipient's unread rows about one item read at {@code at}; returns how many. */
    int markRead( String recipient, long itemId, Instant at ) throws SQLException;

    /** Marks every unread row in the given projects read at {@code at}; returns how many. */
    int markAllRead( String recipient, Set< Long > projectIds, Instant at ) throws SQLException;

    /** {@link EmailMode#EACH} without a stored preference. */
    EmailMode emailMode( String login ) throws SQLException;

    void setEmailMode( String login, EmailMode mode ) throws SQLException;

    /**
     * Notices not yet mailed and created at or after {@code since}, with each person's email choice and, for a
     * comment event, the comment's text (null when removed or not a comment). Ordered by recipient, then id. Only
     * rows that can be mailed now: people who chose {@code off} are left out, and so are daily people unless
     * {@code dailyOpen} (the digest hour has come) and their last digest was before {@code today}.
     */
    List< PendingEmail > pendingEmail( Instant since, int limit, boolean dailyOpen, LocalDate today )
        throws SQLException;

    /**
     * Stamps the recipient's rows {@code emailed_at = at}, only those still unstamped, in one transaction. A
     * non-null {@code digestOn} also records the day of the person's last daily digest (their mode is kept).
     */
    void markEmailed( String recipient, Collection< Long > ids, Instant at, LocalDate digestOn ) throws SQLException;
}

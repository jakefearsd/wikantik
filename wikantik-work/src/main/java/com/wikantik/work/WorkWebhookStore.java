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

import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Webhook rows and their delivery cursor. Every state change writes its {@code settings} event (note
 * {@code webhook}, {@code from_value} the host, {@code to_value} what happened) in the same transaction. No
 * visibility check here: {@link WorkWebhooksService} is the gate.
 */
public interface WorkWebhookStore {

    /**
     * Adds a webhook, owned by {@code by}, whose cursor starts at the newest event that exists now (history is
     * never replayed); writes an {@code added} event. One transaction.
     */
    Webhook add( Project p, URI url, WebhookFormat format, String secret, List< String > eventTypes, Actor by )
        throws SQLException;

    Optional< Webhook > byId( long id ) throws SQLException;

    /** The project's webhooks, oldest first. */
    List< Webhook > forProject( long projectId ) throws SQLException;

    /** Every webhook that is not switched off, across projects, oldest first. */
    List< Webhook > enabled() throws SQLException;

    /**
     * Changes a webhook; null arguments leave a part alone. The updater becomes the owner. Switching a stopped one
     * on clears its failure and moves the cursor to the newest event now, so a day of backlog is not sent. Events:
     * {@code changed} (address, event types or owner), {@code enabled}, {@code disabled}. One transaction.
     */
    Webhook update( Webhook current, URI url, List< String > eventTypes, Boolean enabled, Actor by ) throws SQLException;

    /** Deletes the row and writes a {@code removed} event (the event log keeps the history). One transaction. */
    void remove( Webhook current, Actor by ) throws SQLException;

    /**
     * The item events after the webhook's cursor, in (transaction, id) order, only from transactions that have
     * ended. Not filtered by event type, so the cursor passes over what the webhook does not send.
     */
    List< WebhookEvent > pending( Webhook w, int limit ) throws SQLException;

    /**
     * Moves the cursor from the given pair to the new one, only if it is still at the given pair. With
     * {@code deliveredAt} not null it also stamps the delivery and clears the failure.
     *
     * @return whether the cursor moved
     */
    boolean advance( long id, String fromTx, long fromEventId, String toTx, long toEventId, Instant deliveredAt )
        throws SQLException;

    /** Notes a failed attempt: keeps the first {@code failing_since} and stores {@code error} (host-only text). */
    void recordFailure( long id, Instant at, String error ) throws SQLException;

    /**
     * Switches the webhook off after too long a failure and writes the {@code failed} event, attributed to its
     * owner, in one transaction; nothing is written when it was already off.
     *
     * @return whether this call switched it off
     */
    boolean disableForFailure( Webhook w, Instant at ) throws SQLException;

    /**
     * Moves the cursor to the newest event that exists now (never backwards): what a webhook that cannot send, or is
     * off, does every cycle so no backlog builds up behind its endpoint.
     */
    void skipToNewest( long id ) throws SQLException;
}

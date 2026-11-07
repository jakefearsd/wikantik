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
package com.wikantik.collab.testing;

import com.wikantik.collab.testing.SimServer.Update;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The in-flight messages of one simulated run (F1): client-to-server requests and server-to-client answers, in send
 * order. Nothing is delivered unless the scheduler says so, so reordering, loss and duplication are free.
 *
 * <p>Message ids are {@code <creating step id>.<n>}: stable when the shrinker removes unrelated steps, so a later
 * {@code DELIVER} still names the same message.</p>
 */
public final class SimNetwork {

    /** Requests carry the protocol call; answers carry the server's result for {@code requestId}. */
    public enum Type { PUSH, PULL, PUBLISH, JOIN, LEAVE, PUSH_RESULT, PULL_RESULT, PUBLISH_RESULT, JOINED, LEFT;

        public boolean request() {
            return ordinal() <= LEAVE.ordinal();
        }
    }

    /**
     * One message. Requests: {@code participantId}, {@code version} (push/publish base, pull {@code since}) and, for a
     * push, {@code updates}. Answers: {@code requestId} and {@code result} (a {@code PushResult}, {@code PullResult},
     * {@code PublishResult}, {@code Joined}, or none for {@code LEFT}).
     */
    public record Message( String id, Type type, int client, String requestId, String participantId, int version,
                           List< Update > updates, Object result ) {

        public Message withId( final String newId ) {
            return new Message( newId, type, client, requestId, participantId, version, updates, result );
        }
    }

    private final Map< String, Message > pending = new LinkedHashMap<>();

    public void send( final Message m ) {
        if( pending.putIfAbsent( m.id(), m ) != null ) {
            throw new IllegalStateException( "duplicate message id " + m.id() );
        }
    }

    /** Removes and returns the message, or null when it is not (or no longer) in flight. */
    public Message take( final String id ) {
        return pending.remove( id );
    }

    /** The message without removing it, or null. */
    public Message peek( final String id ) {
        return pending.get( id );
    }

    public boolean contains( final String id ) {
        return pending.containsKey( id );
    }

    /** In-flight ids in send order. */
    public List< String > ids() {
        return new ArrayList<>( pending.keySet() );
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int size() {
        return pending.size();
    }
}

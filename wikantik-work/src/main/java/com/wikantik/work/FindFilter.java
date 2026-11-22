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

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * A search over work items across projects. Every field is optional: an empty set, null or false means "any".
 * {@code assignee} and {@code reporter} hold {@link #ME}, a display name or a login as typed; they are resolved at
 * the service. {@code limit} of zero means the default page size; {@code cursor} is the opaque marker of the last
 * row of the previous page.
 */
public record FindFilter( Set< String > projectKeys, Set< WorkStatus > statuses, Set< WorkLevel > levels,
                          WorkType type, Set< String > labels, Set< Integer > priorities,
                          String assignee, String queue, String reporter, String productKey,
                          SprintRef sprint, boolean watching, String text, LocalDate dueBefore,
                          Instant updatedSince, Sort sort, int limit, String cursor ) {

    /** The value of {@code assignee} or {@code reporter} that means the caller. */
    public static final String ME = "me";

    /** The order of a page. Every order ends on the item id, so a cursor is stable under ties. */
    public enum Sort implements WireEnum {
        UPDATED( "updated" ), PRIORITY( "priority" ), DUE( "due" ), CREATED( "created" );

        private final String wire;

        Sort( final String wire ) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }

        /** Parses a wire value; throws {@link WorkValidationException} on field {@code sort} when unknown. */
        public static Sort fromWire( final String v ) {
            return WireEnum.parse( Sort.class, v, "sort" );
        }
    }

    /** Null sets become empty immutable copies; a null sort becomes {@link Sort#UPDATED}. */
    public FindFilter {
        projectKeys = projectKeys == null ? Set.of() : Set.copyOf( projectKeys );
        statuses = statuses == null ? Set.of() : Set.copyOf( statuses );
        levels = levels == null ? Set.of() : Set.copyOf( levels );
        labels = labels == null ? Set.of() : Set.copyOf( labels );
        priorities = priorities == null ? Set.of() : Set.copyOf( priorities );
        sort = sort == null ? Sort.UPDATED : sort;
    }

    /** The filter that matches everything: nothing set, sorted by update, default limit, first page. */
    public static FindFilter all() {
        return new FindFilter( null, null, null, null, null, null, null, null, null, null, null, false, null, null,
            null, null, 0, null );
    }

    /** This filter with another cursor and limit; everything else kept. */
    public FindFilter page( final String newCursor, final int newLimit ) {
        return new FindFilter( projectKeys, statuses, levels, type, labels, priorities, assignee, queue, reporter,
            productKey, sprint, watching, text, dueBefore, updatedSince, sort, newLimit, newCursor );
    }
}

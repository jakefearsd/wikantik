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
package com.wikantik.rest;

import com.wikantik.work.ItemFilter;
import com.wikantik.work.Product;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkType;
import com.wikantik.work.WorkValidationException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reads the list-query parameters of the work API into an {@link ItemFilter}. */
final class WorkFilters {

    private WorkFilters() {
    }

    /** The list-query filter; {@code withStatus} reads the {@code status} parameter too. */
    static ItemFilter filter( final WorkResourceBase.Call c, final boolean withStatus, final int limit )
            throws Exception {
        final WorkLevel level = c.query( "level" ) == null ? null : WorkLevel.fromWire( c.query( "level" ) );
        final Long parentId = c.query( "parent" ) == null ? null : c.svc.item( c.actor, c.query( "parent" ) ).item().id();
        final String type = c.query( "type" );
        final List< String > labels = c.queryValues( "label" );
        return new ItemFilter( null, withStatus ? statuses( c ) : null, c.query( "assignee" ), productId( c ), level,
            parentId, c.query( "q" ), dueBefore( c ), limit, order( c.query( "order" ) ), c.query( "queue" ),
            type == null ? null : WorkType.fromWire( type ), labels.isEmpty() ? null : new LinkedHashSet<>( labels ) );
    }

    private static Set< WorkStatus > statuses( final WorkResourceBase.Call c ) {
        if ( c.query( "status" ) == null ) {
            return null;
        }
        final Set< WorkStatus > statuses = EnumSet.noneOf( WorkStatus.class );
        for ( final String s : c.query( "status" ).split( "," ) ) {
            statuses.add( WorkStatus.fromWire( s.trim() ) );
        }
        return statuses;
    }

    private static Long productId( final WorkResourceBase.Call c ) throws Exception {
        if ( c.query( "product" ) == null ) {
            return null;
        }
        Long productId = -1L;
        for ( final Product p : c.svc.listProducts( c.actor ) ) {
            if ( p.key().equals( c.query( "product" ) ) ) {
                productId = p.id();
            }
        }
        return productId;
    }

    private static LocalDate dueBefore( final WorkResourceBase.Call c ) {
        if ( c.query( "dueBefore" ) == null ) {
            return null;
        }
        try {
            return LocalDate.parse( c.query( "dueBefore" ) );
        } catch ( final DateTimeParseException e ) {
            throw new WorkValidationException( "dueBefore", "dueBefore must be a date such as 2026-12-31." );
        }
    }

    /** {@code key} (the default) or {@code due}: soonest due date first, no date last. */
    private static ItemFilter.Order order( final String wire ) {
        if ( wire == null ) {
            return ItemFilter.Order.KEY;
        }
        for ( final ItemFilter.Order o : ItemFilter.Order.values() ) {
            if ( o.wire().equals( wire ) ) {
                return o;
            }
        }
        throw new WorkValidationException( "order", "order must be key or due." );
    }
}

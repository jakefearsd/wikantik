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

import com.wikantik.work.FindParams;
import com.wikantik.work.ItemFilter;
import com.wikantik.work.Product;
import com.wikantik.work.SprintRef;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkType;
import com.wikantik.work.WorkValidationException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            type == null ? null : WorkType.fromWire( type ), labels.isEmpty() ? null : new LinkedHashSet<>( labels ),
            SprintRef.parse( c.query( "sprint" ) ) );
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

    /** The find parameters that take several values: repeats and commas mean the same thing. */
    private static final Set< String > MULTI = Set.of( "projects", "status", "level", "label", "priority" );

    /**
     * The find-mode parameters, name to text, ready for {@link FindParams#parse}. Only {@link FindParams#NAMES} are
     * read, so any other query parameter is ignored (as the project list ignores unknown ones). Only the multi-valued
     * names are split on commas; {@code q} and display names such as "Smith, Jo" reach the parser as typed.
     */
    static Map< String, String > findParams( final WorkResourceBase.Call c ) {
        final Map< String, String > out = new LinkedHashMap<>();
        for ( final String name : FindParams.NAMES ) {
            final String value = MULTI.contains( name ) ? joined( c, name ) : c.query( name );
            if ( value != null ) {
                out.put( name, value );
            }
        }
        return out;
    }

    private static String joined( final WorkResourceBase.Call c, final String name ) {
        List< String > values = c.queryValues( name );
        if ( values.isEmpty() && c.query( name ) != null ) {
            values = List.of( c.query( name ).split( "," ) );
        }
        final List< String > kept = values.stream().map( String::trim ).filter( v -> !v.isEmpty() ).toList();
        return kept.isEmpty() ? null : String.join( ",", kept );
    }

    /** With a project: a find-only parameter is a 400 naming it. */
    static void refuseFindOnly( final WorkResourceBase.Call c ) {
        for ( final String name : FindParams.FIND_ONLY ) {
            if ( c.query( name ) != null ) {
                throw new WorkValidationException( name,
                    name + " is a find parameter; leave out project= to search across projects." );
            }
        }
    }

    /** Without a project: a list-only parameter is a 400 naming it. */
    static void refuseListOnly( final WorkResourceBase.Call c ) {
        for ( final String name : List.of( "parent", "order" ) ) {
            if ( c.query( name ) != null ) {
                throw new WorkValidationException( name, name + " applies to one project's list." );
            }
        }
    }
}

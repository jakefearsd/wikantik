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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wikantik.work.ItemFilter;
import com.wikantik.work.Product;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkValidationException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Set;

/** Reading a work request: JSON fields and the list-query filter, each failure a 400 naming its field. */
final class WorkRequests {

    private WorkRequests() {
    }

    /** A required string field; missing or blank is a 400 naming the field. */
    static String requiredString( final JsonObject body, final String field ) {
        final String v = optString( body, field );
        if ( v == null || v.isBlank() ) {
            throw new WorkValidationException( field, field + " is required." );
        }
        return v;
    }

    /** A string field, null when absent or JSON null; a non-string value is a 400. */
    static String optString( final JsonObject body, final String field ) {
        final JsonElement v = body.get( field );
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        if ( !v.isJsonPrimitive() ) {
            throw new WorkValidationException( field, field + " must be text." );
        }
        return v.getAsString();
    }

    /** Converts one JSON value of a PATCH {@code changes} object to a plain String, Number, Boolean or null. */
    static Object plainValue( final String field, final JsonElement v ) {
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        if ( !v.isJsonPrimitive() ) {
            throw new WorkValidationException( field, field + " must be a plain value." );
        }
        final JsonPrimitive p = v.getAsJsonPrimitive();
        return p.isBoolean() ? ( Object ) p.getAsBoolean() : p.isNumber() ? ( Object ) p.getAsNumber() : p.getAsString();
    }

    /** The list-query filter; {@code withStatus} reads the {@code status} parameter too. */
    static ItemFilter filter( final WorkResourceBase.Call c, final boolean withStatus, final int limit )
            throws Exception {
        Set< WorkStatus > statuses = null;
        if ( withStatus && c.query( "status" ) != null ) {
            statuses = EnumSet.noneOf( WorkStatus.class );
            for ( final String s : c.query( "status" ).split( "," ) ) {
                statuses.add( WorkStatus.fromWire( s.trim() ) );
            }
        }
        Long productId = null;
        if ( c.query( "product" ) != null ) {
            productId = -1L;
            for ( final Product p : c.svc.listProducts( c.actor ) ) {
                if ( p.key().equals( c.query( "product" ) ) ) {
                    productId = p.id();
                }
            }
        }
        final WorkLevel level = c.query( "level" ) == null ? null : WorkLevel.fromWire( c.query( "level" ) );
        final Long parentId = c.query( "parent" ) == null ? null : c.svc.item( c.actor, c.query( "parent" ) ).item().id();
        LocalDate dueBefore = null;
        if ( c.query( "dueBefore" ) != null ) {
            try {
                dueBefore = LocalDate.parse( c.query( "dueBefore" ) );
            } catch ( final DateTimeParseException e ) {
                throw new WorkValidationException( "dueBefore", "dueBefore must be a date such as 2026-12-31." );
            }
        }
        return new ItemFilter( null, statuses, c.query( "assignee" ), productId, level, parentId, c.query( "q" ),
            dueBefore, limit, order( c.query( "order" ) ) );
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

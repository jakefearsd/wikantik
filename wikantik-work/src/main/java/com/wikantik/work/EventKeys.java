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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns the database ids that parent and product {@code field} events store into the keys a reader knows, at read
 * time: {@code parent_id} 42 becomes {@code parent} PLAT-3, {@code product_id} 7 becomes {@code product} web. A parent
 * is only named when it is in the event's own project (it always is; the check keeps that true), so nothing outside
 * the project the caller can see is ever named.
 */
final class EventKeys {

    static final String UNKNOWN = "unknown";
    private static final Pattern DIGITS = Pattern.compile( "\\d{1,18}" );

    private EventKeys() {
    }

    static List< WorkEvent > resolve( final WorkStore store, final long projectId, final List< WorkEvent > events )
            throws SQLException {
        final Map< String, String > products = new HashMap<>();
        final Map< String, String > parents = new HashMap<>();
        boolean productsLoaded = false;
        final List< WorkEvent > out = new ArrayList<>( events.size() );
        for ( final WorkEvent e : events ) {
            if ( !WorkEvent.FIELD.equals( e.type() ) ) {
                out.add( e );
            } else if ( "parent_id".equals( e.note() ) ) {
                out.add( renamed( e, "parent", parentKey( store, projectId, parents, e.fromValue() ),
                    parentKey( store, projectId, parents, e.toValue() ) ) );
            } else if ( "product_id".equals( e.note() ) ) {
                if ( !productsLoaded ) {
                    store.products().forEach( p -> products.put( String.valueOf( p.id() ), p.key() ) );
                    productsLoaded = true;
                }
                out.add( renamed( e, "product", keyOrNull( products, e.fromValue() ),
                    keyOrNull( products, e.toValue() ) ) );
            } else {
                out.add( e );
            }
        }
        return out;
    }

    private static String keyOrNull( final Map< String, String > keys, final String id ) {
        return id == null ? null : keys.getOrDefault( id, UNKNOWN );
    }

    private static String parentKey( final WorkStore store, final long projectId, final Map< String, String > cache,
                                     final String id ) throws SQLException {
        if ( id == null ) {
            return null;
        }
        String key = cache.get( id );
        if ( key == null ) {
            // Only ever a number this module wrote; anything else is shown as unknown, never echoed.
            final Optional< Item > parent = DIGITS.matcher( id ).matches()
                ? store.itemById( Long.parseLong( id ) ) : Optional.empty();
            key = parent.filter( p -> p.projectId() == projectId ).map( Item::key ).orElse( UNKNOWN );
            cache.put( id, key );
        }
        return key;
    }

    private static WorkEvent renamed( final WorkEvent e, final String field, final String from, final String to ) {
        return new WorkEvent( e.id(), e.projectId(), e.itemId(), e.itemKey(), e.at(), e.actor(), e.actorKind(),
            e.agentId(), e.type(), from, to, field );
    }
}

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

import java.sql.Date;
import java.util.Collections;
import java.util.List;

/** The WHERE clause an {@link ItemFilter} describes, as SQL text and positional parameters. */
final class ItemQuery {

    private ItemQuery() {
    }

    /** Appends the WHERE conditions of {@code f} (and their parameters) to the builders. */
    static void conditions( final ItemFilter f, final boolean withStatuses, final StringBuilder where,
                            final List< Object > params ) {
        clause( where, params, " AND i.project_id = ?", f.projectId() );
        if ( withStatuses && f.statuses() != null && !f.statuses().isEmpty() ) {
            where.append( " AND i.status IN (" + String.join( ",", Collections.nCopies( f.statuses().size(), "?" ) )
                + ")" );
            f.statuses().stream().map( WorkStatus::wire ).sorted().forEach( params::add );
        }
        clause( where, params, " AND i.assignee = ?", f.assignee() );
        clause( where, params, " AND i.product_id = ?", f.productId() );
        clause( where, params, " AND i.level = ?", f.level() == null ? null : f.level().wire() );
        clause( where, params, " AND i.parent_id = ?", f.parentId() );
        text( where, params, f.text() );
        clause( where, params, " AND i.due_on < ?", f.dueBefore() == null ? null : Date.valueOf( f.dueBefore() ) );
    }

    private static void clause( final StringBuilder where, final List< Object > params, final String sql,
                                final Object value ) {
        if ( value != null ) {
            where.append( sql );
            params.add( value );
        }
    }

    private static void text( final StringBuilder where, final List< Object > params, final String text ) {
        if ( text != null && !text.isBlank() ) {
            where.append( " AND ( i.title ILIKE ? ESCAPE '\\' OR i.body ILIKE ? ESCAPE '\\' )" );
            final String like = "%" + text.replace( "\\", "\\\\" ).replace( "%", "\\%" ).replace( "_", "\\_" ) + "%";
            params.add( like );
            params.add( like );
        }
    }
}

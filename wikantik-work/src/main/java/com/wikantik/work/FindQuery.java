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

import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The one statement of a cross-project find. Visibility is a predicate of the statement itself: the visible, named
 * project ids are bound as a {@code bigint[]} and every other condition only narrows. All user text (the search
 * text, labels, names) is a bound parameter.
 */
final class FindQuery {

    /** The statement text and its positional parameters. */
    record Built( String sql, List< Object > params ) {
    }

    private static final String DUE_EXPR = "COALESCE( i.due_on, DATE '9999-12-31' )";

    private final StringBuilder where = new StringBuilder( " WHERE i.project_id = ANY( ? )" );
    private final List< Object > params = new ArrayList<>();
    private final Connection conn;

    private FindQuery( final Connection conn ) {
        this.conn = conn;
    }

    /** Builds the statement for {@code spec}; the connection only makes the array parameters. */
    static Built build( final FindSpec spec, final Connection conn ) throws SQLException {
        final FindQuery q = new FindQuery( conn );
        q.params.add( q.array( "bigint", spec.projectIds().stream().sorted().toArray( Long[]::new ) ) );
        q.enums( spec );
        q.people( spec );
        q.scalars( spec );
        q.sprintAndWatch( spec );
        ItemQuery.text( q.where, q.params, spec.text() );
        q.dates( spec );
        q.cursor( spec );
        q.params.add( spec.fetch() );
        return new Built( JdbcWorkStore.ITEM_SELECT + q.where + orderBy( spec.sort() ) + " LIMIT ?", q.params );
    }

    private void enums( final FindSpec spec ) throws SQLException {
        status( spec.statuses() );
        level( spec.levels() );
        type( spec.type() );
        labels( spec.labels() );
        priorities( spec.priorities() );
    }

    private void people( final FindSpec spec ) {
        add( " AND i.assignee = ?", spec.assigneeLogin() );
        add( " AND i.claimed_by_agent = ?", spec.assigneeAgentId() );
        add( " AND i.assignee_queue = ?", spec.queue() );
        if ( spec.reporterLogin() != null ) {
            add( " AND i.reporter = ? AND i.reporter_agent_id IS NULL", spec.reporterLogin() );
        }
        add( " AND i.reporter_agent_id = ?", spec.reporterAgentId() );
    }

    private void scalars( final FindSpec spec ) {
        add( " AND i.product_id = ?", spec.productId() );
    }

    private void sprintAndWatch( final FindSpec spec ) {
        ItemQuery.sprint( spec.sprint(), where, params );
        if ( spec.watcherLogin() != null ) {
            where.append( " AND EXISTS ( SELECT 1 FROM work_watch w WHERE w.item_id = i.id AND w.login = ? "
                + "AND w.state = 'watching' )" );
            params.add( spec.watcherLogin() );
        }
    }

    private void dates( final FindSpec spec ) {
        if ( spec.dueBefore() != null ) {
            add( " AND i.due_on < ?", Date.valueOf( spec.dueBefore() ) );
        }
        if ( spec.updatedSince() != null ) {
            add( " AND i.updated_at >= ?", Timestamp.from( spec.updatedSince() ) );
        }
    }

    private void status( final Set< WorkStatus > statuses ) throws SQLException {
        if ( !statuses.isEmpty() ) {
            where.append( " AND i.status = ANY( ? )" );
            params.add( array( "text", statuses.stream().map( WorkStatus::wire ).sorted().toArray( String[]::new ) ) );
        }
    }

    private void level( final Set< WorkLevel > levels ) throws SQLException {
        if ( !levels.isEmpty() ) {
            where.append( " AND i.level = ANY( ? )" );
            params.add( array( "text", levels.stream().map( WorkLevel::wire ).sorted().toArray( String[]::new ) ) );
        }
    }

    private void type( final WorkType type ) {
        add( " AND i.type = ?", type == null ? null : type.wire() );
    }

    private void labels( final Set< String > labels ) {
        if ( !labels.isEmpty() ) {
            where.append( " AND i.labels @> ?::text[]" );
            params.add( ItemQuery.textArray( labels ) );
        }
    }

    private void priorities( final Set< Integer > priorities ) throws SQLException {
        if ( !priorities.isEmpty() ) {
            where.append( " AND i.priority = ANY( ? )" );
            params.add( array( "int4", priorities.stream().sorted().toArray( Integer[]::new ) ) );
        }
    }

    /** The page boundary: a row comparison on (sort value, id), in the direction of the sort. */
    private void cursor( final FindSpec spec ) {
        final FindCursor after = spec.after();
        if ( after != null ) {
            final String op = descending( spec.sort() ) ? "<" : ">";
            where.append( " AND ( " ).append( expr( spec.sort() ) ).append( ", i.id ) " ).append( op )
                .append( " ( ?, ? )" );
            params.add( after.sqlValue() );
            params.add( after.id() );
        }
    }

    private static String orderBy( final FindFilter.Sort sort ) {
        final String dir = descending( sort ) ? " DESC" : " ASC";
        return " ORDER BY " + expr( sort ) + dir + ", i.id" + dir;
    }

    private static boolean descending( final FindFilter.Sort sort ) {
        return sort == FindFilter.Sort.UPDATED || sort == FindFilter.Sort.CREATED;
    }

    private static String expr( final FindFilter.Sort sort ) {
        return switch ( sort ) {
            case UPDATED -> "i.updated_at";
            case CREATED -> "i.created_at";
            case PRIORITY -> "i.priority";
            case DUE -> DUE_EXPR;
        };
    }

    private void add( final String sql, final Object value ) {
        if ( value != null ) {
            where.append( sql );
            params.add( value );
        }
    }

    private java.sql.Array array( final String type, final Object[] values ) throws SQLException {
        return conn.createArrayOf( type, values );
    }
}

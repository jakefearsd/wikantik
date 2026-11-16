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

import com.wikantik.jdbc.Jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Loads a {@link PolicyChain}: the item, its ancestors, its project and its product. Read-only. */
final class PolicyChains {

    /** Task, story, epic: the hierarchy is three deep and no deeper, so two ancestors at most. */
    private static final int MAX_ANCESTORS = 2;

    private PolicyChains() {
    }

    static PolicyChain load( final Jdbc jdbc, final long itemId ) throws SQLException {
        final Item item = one( jdbc, itemId )
            .orElseThrow( () -> new SQLException( "work_item " + itemId + " not found" ) );
        final List< Item > ancestors = new ArrayList<>();
        Long parent = item.parentId();
        while ( parent != null && ancestors.size() < MAX_ANCESTORS ) {
            final Optional< Item > next = one( jdbc, parent );
            if ( next.isEmpty() ) {
                break;
            }
            ancestors.add( next.get() );
            parent = next.get().parentId();
        }
        final Project project = jdbc.queryOne( "SELECT " + JdbcWorkCatalog.PROJECT_COLUMNS
                + " FROM work_project WHERE id = ?", ps -> ps.setLong( 1, item.projectId() ),
            JdbcWorkCatalog.PROJECT_ROW )
            .orElseThrow( () -> new SQLException( "work_project " + item.projectId() + " not found" ) );
        final Product product = item.productId() == null ? null : jdbc.queryOne( "SELECT "
                + JdbcWorkCatalog.PRODUCT_COLUMNS + " FROM work_product WHERE id = ?",
            ps -> ps.setLong( 1, item.productId() ), JdbcWorkCatalog.PRODUCT_ROW ).orElse( null );
        return new PolicyChain( item, ancestors, project, product );
    }

    private static Optional< Item > one( final Jdbc jdbc, final long id ) throws SQLException {
        return jdbc.queryOne( JdbcWorkStore.ITEM_SELECT + " WHERE i.id = ?", ps -> ps.setLong( 1, id ),
            JdbcWorkStore.ITEM_ROW );
    }
}

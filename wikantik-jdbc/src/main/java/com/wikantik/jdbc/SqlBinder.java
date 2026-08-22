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
package com.wikantik.jdbc;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Binds parameters onto a freshly-prepared statement. Stateless — no SQL, no mapping. */
@FunctionalInterface
public interface SqlBinder {
    void bind( PreparedStatement ps ) throws SQLException;

    /** Shared no-op binder for parameterless statements. */
    SqlBinder NONE = ps -> { };

    /**
     * Binds {@code params} to {@code ?} placeholders 1..N in order. Factors out the
     * {@code for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i))}
     * loop repeated at almost every dynamic-filter call site across the KG repositories.
     */
    static SqlBinder positional( final List< Object > params ) {
        return ps -> {
            for ( int i = 0; i < params.size(); i++ ) ps.setObject( i + 1, params.get( i ) );
        };
    }
}

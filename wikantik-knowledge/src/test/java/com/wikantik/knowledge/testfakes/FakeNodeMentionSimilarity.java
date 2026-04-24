/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge.testfakes;

import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity.ScoredName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Scripts similarTo(name, limit) for a given page name. */
public class FakeNodeMentionSimilarity extends NodeMentionSimilarity {

    private final Map< String, List< ScoredName > > scripted = new HashMap<>();

    public FakeNodeMentionSimilarity() {
        super( noOpDataSource(), "fake-model" );
    }

    public void setRelated( final String page, final List< ScoredName > neighbors ) {
        scripted.put( page, List.copyOf( neighbors ) );
    }

    @Override public boolean isReady() { return true; }

    @Override public List< ScoredName > similarTo( final String name, final int limit ) {
        final List< ScoredName > full = scripted.getOrDefault( name, List.of() );
        return full.size() <= limit ? full : full.subList( 0, limit );
    }

    /** Returns a DataSource stub that satisfies the non-null guard in the super constructor. */
    private static DataSource noOpDataSource() {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                throw new UnsupportedOperationException( "FakeNodeMentionSimilarity stub" );
            }
            @Override public Connection getConnection( String u, String p ) throws SQLException {
                throw new UnsupportedOperationException( "FakeNodeMentionSimilarity stub" );
            }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter( PrintWriter out ) {}
            @Override public void setLoginTimeout( int seconds ) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }
            @Override public < T > T unwrap( Class< T > iface ) throws SQLException {
                throw new SQLException( "not a wrapper" );
            }
            @Override public boolean isWrapperFor( Class< ? > iface ) { return false; }
        };
    }
}

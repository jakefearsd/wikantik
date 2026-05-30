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
package com.wikantik.auth.authorize;

import com.wikantik.api.core.Engine;
import com.wikantik.auth.NoSuchPrincipalException;
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.WikiSecurityException;

import java.security.Principal;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * In-memory {@link GroupDatabase} for tests, seeded with the same fixture groups that the
 * retired {@code groupdatabase.xml} provided (TV, Literature, Art, Admin). It exists so the
 * {@code TestEngine}-based suite keeps running with zero infrastructure after the XML-backed
 * group store was removed in favour of PostgreSQL ({@link JDBCGroupDatabase}) for production.
 *
 * <p>Semantics mirror the retired XML store: {@link #save} stamps create/modify metadata and
 * stores the group, {@link #delete} throws {@link NoSuchPrincipalException} when absent, and
 * {@link #groups} returns every stored group.</p>
 */
public class InMemoryGroupDatabase implements GroupDatabase {

    private final Map< String, Group > groups = new LinkedHashMap<>();
    private Engine engine;

    @Override
    public void initialize( final Engine engine, final Properties props ) {
        this.engine = engine;
        seed();
    }

    private void seed() {
        if ( !groups.isEmpty() ) {
            return;
        }
        addGroup( "TV", "Archie Bunker", "BullwinkleMoose", "Fred Friendly" );
        addGroup( "Literature", "Charles Dickens", "Homer" );
        addGroup( "Art" );
        addGroup( "Admin", "Administrator" );
    }

    private void addGroup( final String name, final String... members ) {
        final String wiki = engine == null ? "Wikantik" : engine.getApplicationName();
        final Group group = new Group( name, wiki );
        for ( final String member : members ) {
            group.add( new WikiPrincipal( member ) );
        }
        final Date epoch = new Date( 0L );
        group.setCreated( epoch );
        group.setLastModified( epoch );
        group.setCreator( "" );
        group.setModifier( "" );
        groups.put( name, group );
    }

    @Override
    public Group[] groups() {
        final Collection< Group > all = groups.values();
        return all.toArray( new Group[0] );
    }

    @Override
    public void save( final Group group, final Principal modifier ) throws WikiSecurityException {
        if ( group == null || modifier == null ) {
            throw new IllegalArgumentException( "Group or modifier cannot be null." );
        }
        final String index = group.getName();
        final boolean isNew = !groups.containsKey( index );
        final Date modDate = new Date( System.currentTimeMillis() );
        if ( isNew ) {
            group.setCreated( modDate );
            group.setCreator( modifier.getName() );
        }
        group.setModifier( modifier.getName() );
        group.setLastModified( modDate );
        groups.put( index, group );
    }

    @Override
    public void delete( final Group group ) throws WikiSecurityException {
        if ( groups.remove( group.getName() ) == null ) {
            throw new NoSuchPrincipalException( "Not in database: " + group.getName() );
        }
    }
}

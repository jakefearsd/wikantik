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

import com.wikantik.work.WorkValidationException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/** {@code /api/work/activity}: a project's event stream, newest first. */
public class WorkActivityResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void routes( final List< Object[] > r ) {
        route( r, "GET", "activity", c -> {
            final String project = c.query( "project" );
            if ( project == null ) {
                throw new WorkValidationException( "project", "project is required." );
            }
            Instant before = null;
            if ( c.query( "before" ) != null ) {
                try {
                    before = Instant.parse( c.query( "before" ) );
                } catch ( final DateTimeParseException e ) {
                    throw new WorkValidationException( "before", "before must be an ISO-8601 instant." );
                }
            }
            c.json( c.svc.activity( c.actor, project, before, clampLimit( c ) ) );
        } );
    }
}

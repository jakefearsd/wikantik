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

import com.google.gson.JsonObject;

import java.util.List;

/** {@code /api/work/me}: what is waiting on the caller, and the projects they can see. */
public class WorkMeResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void routes( final List< Object[] > r ) {
        route( r, "GET", "me", c -> {
            final JsonObject out = new JsonObject();
            out.add( "waitingOn", toTree( c.svc.waitingOn( c.actor ), c.actor ) );
            out.add( "projects", visibleProjects( c ) );
            c.json( out );
        } );
    }
}

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
package com.wikantik.scim;

import com.google.gson.JsonObject;

/** Builds a SCIM error response body (RFC 7644 §3.12). */
public final class ScimError {
    private ScimError() {}
    public static JsonObject body( final int status, final String scimType, final String detail ) {
        final JsonObject o = new JsonObject();
        final com.google.gson.JsonArray schemas = new com.google.gson.JsonArray();
        schemas.add( "urn:ietf:params:scim:api:messages:2.0:Error" );
        o.add( "schemas", schemas );
        o.addProperty( "status", String.valueOf( status ) );
        if ( scimType != null ) o.addProperty( "scimType", scimType );
        if ( detail != null ) o.addProperty( "detail", detail );
        return o;
    }
}

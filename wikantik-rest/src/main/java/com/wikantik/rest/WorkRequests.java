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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wikantik.work.WorkValidationException;

import java.util.ArrayList;
import java.util.List;

/** Reading a work request: JSON fields and the list-query filter, each failure a 400 naming its field. */
final class WorkRequests {

    private WorkRequests() {
    }

    /** A required string field; missing or blank is a 400 naming the field. */
    static String requiredString( final JsonObject body, final String field ) {
        final String v = optString( body, field );
        if ( v == null || v.isBlank() ) {
            throw new WorkValidationException( field, field + " is required." );
        }
        return v;
    }

    /** A string field, null when absent or JSON null; a non-string value is a 400. */
    static String optString( final JsonObject body, final String field ) {
        final JsonElement v = body.get( field );
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        if ( !v.isJsonPrimitive() ) {
            throw new WorkValidationException( field, field + " must be text." );
        }
        return v.getAsString();
    }

    /** A list of strings, null when absent or JSON null; anything else is a 400 naming the field. */
    static List< String > optStringList( final JsonObject body, final String field ) {
        final JsonElement v = body.get( field );
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        if ( !v.isJsonArray() ) {
            throw new WorkValidationException( field, field + " must be a list of text." );
        }
        final List< String > out = new ArrayList<>();
        for ( final JsonElement e : v.getAsJsonArray() ) {
            if ( !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() ) {
                throw new WorkValidationException( field, field + " must be a list of text." );
            }
            out.add( e.getAsString() );
        }
        return out;
    }

    /** Converts one JSON value of a PATCH {@code changes} object to a plain String, Number, Boolean or null. */
    static Object plainValue( final String field, final JsonElement v ) {
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        if ( v.isJsonArray() ) {
            final List< Object > values = new ArrayList<>();
            for ( final JsonElement e : v.getAsJsonArray() ) {
                if ( e.isJsonArray() || e.isJsonObject() ) {
                    throw new WorkValidationException( field, field + " must be a plain value." );
                }
                values.add( plainValue( field, e ) );
            }
            return values;
        }
        if ( !v.isJsonPrimitive() ) {
            throw new WorkValidationException( field, field + " must be a plain value." );
        }
        final JsonPrimitive p = v.getAsJsonPrimitive();
        return p.isBoolean() ? ( Object ) p.getAsBoolean() : p.isNumber() ? ( Object ) p.getAsNumber() : p.getAsString();
    }
}

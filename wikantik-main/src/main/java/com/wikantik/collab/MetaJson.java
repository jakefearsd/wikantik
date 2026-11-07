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
package com.wikantik.collab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one JSON normalisation of page meta (A14). Page meta arrives from SnakeYAML (Integer, Float, Date), from the wire
 * and from the store's JSONB (Long or Double): two values are compared only after both went through {@link #normalise},
 * so a YAML {@code 3} and a JSON {@code 3} are equal. {@link #GSON} is also the store adapter's codec.
 */
public final class MetaJson {

    /** Nulls kept (an empty YAML field), numbers read as Long when integral, else Double. */
    public static final Gson GSON = new GsonBuilder().serializeNulls()
        .setObjectToNumberStrategy( ToNumberPolicy.LONG_OR_DOUBLE ).create();
    private static final Type MAP_TYPE = new TypeToken< Map< String, Object > >() { }.getType();

    private MetaJson() {
    }

    /** {@code meta} after a JSON round trip, in its original key order; never null. */
    public static Map< String, Object > normalise( final Map< String, Object > meta ) {
        final Map< String, Object > out = GSON.fromJson( GSON.toJson( meta ), MAP_TYPE );
        return out == null ? new LinkedHashMap<>() : new LinkedHashMap<>( out );
    }
}

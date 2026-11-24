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
package com.wikantik.livevalues;

import com.wikantik.api.livevalues.LiveValueSource;
import com.wikantik.api.livevalues.LiveValues;

import java.util.Map;

/** Test double: turns the one token {@code {{wiki:articles|round=100}}} into {@code 1,100+}; generation is settable. */
public final class StubLiveValues implements LiveValues {

    public static final String TOKEN = "{{wiki:articles|round=100}}";
    public static final String VALUE = "1,100+";

    private volatile String generation = "g1";
    private volatile String value = VALUE;

    /** Changes what the token expands to, as a corpus change would; pair with {@link #generation(String)}. */
    public void value( final String v ) {
        this.value = v;
    }

    public void generation( final String g ) {
        this.generation = g;
    }

    @Override
    public String expandText( final String text ) {
        return text == null ? null : text.replace( TOKEN, value );
    }

    @Override
    public String expandBody( final String markdown ) {
        return expandText( markdown );
    }

    @Override
    public String generation() {
        return generation;
    }

    @Override
    public Map< String, String > renderedValues() {
        return Map.of();
    }

    @Override
    public void register( final LiveValueSource source ) {
        // nothing to register
    }
}

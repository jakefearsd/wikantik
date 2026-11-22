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

import java.math.BigDecimal;
import java.time.Instant;

/** Times a code host gives as numbers rather than text; bounded like {@link HookJson#time}. */
final class HookTimes {

    /** 9999-12-31T23:59:59Z. */
    private static final BigDecimal LAST_EPOCH_SECOND = BigDecimal.valueOf( 253_402_300_799L );

    private HookTimes() {
    }

    /**
     * A time given either as whole seconds since 1970 (a JSON number, as GitHub's {@code repository.pushed_at} is in
     * a push payload) or as text ({@link HookJson#time}); outside 1970 to 9999 it is null.
     */
    static Instant epochOrTime( final JsonObject o, final String key ) {
        final JsonElement v = o == null ? null : o.get( key );
        if ( v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ) {
            final BigDecimal seconds = v.getAsBigDecimal();
            return seconds.signum() < 0 || seconds.compareTo( LAST_EPOCH_SECOND ) > 0 ? null
                : Instant.ofEpochSecond( seconds.longValue() );
        }
        return HookJson.time( o, key );
    }
}

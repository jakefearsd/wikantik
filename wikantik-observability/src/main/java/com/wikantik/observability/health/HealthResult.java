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
package com.wikantik.observability.health;

import java.util.Map;

/**
 * Result of an individual health check, including status, optional response time, and detail.
 *
 * @param status the health status
 * @param responseTimeMs time taken to perform the check, in milliseconds (-1 if not measured)
 * @param detail optional key-value details (e.g., error messages)
 */
public record HealthResult( HealthStatus status, long responseTimeMs, Map<String, String> detail ) {

    public static HealthResult up() {
        return new HealthResult( HealthStatus.UP, -1, Map.of() );
    }

    public static HealthResult up( final long responseTimeMs ) {
        return new HealthResult( HealthStatus.UP, responseTimeMs, Map.of() );
    }

    public static HealthResult down( final String reason ) {
        return new HealthResult( HealthStatus.DOWN, -1, Map.of( "error", reason ) );
    }

    public static HealthResult down( final long responseTimeMs, final String reason ) {
        return new HealthResult( HealthStatus.DOWN, responseTimeMs, Map.of( "error", reason ) );
    }

}

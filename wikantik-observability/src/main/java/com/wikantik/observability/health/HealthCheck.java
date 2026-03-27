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

/**
 * Interface for pluggable health check providers. Each implementation checks one
 * aspect of the system (e.g., database connectivity, search index, engine status).
 */
public interface HealthCheck {

    /**
     * Returns the display name for this health check (e.g., "database", "searchIndex").
     *
     * @return the check name
     */
    String name();

    /**
     * Performs the health check and returns the result.
     *
     * @return the health check result
     */
    HealthResult check();

}

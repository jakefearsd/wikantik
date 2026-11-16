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
package com.wikantik.work;

/**
 * Something work management depends on (the user database, the key store) could not answer just now, so the request
 * cannot be decided either way. Retryable: the REST layer answers 503, the MCP endpoint "Work is unavailable right
 * now."; the cause is logged, never shown. Never a reason to act as if the answer were "no".
 */
public class WorkUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkUnavailableException( final String message ) {
        super( message );
    }

    public WorkUnavailableException( final String message, final Throwable cause ) {
        super( message, cause );
    }
}

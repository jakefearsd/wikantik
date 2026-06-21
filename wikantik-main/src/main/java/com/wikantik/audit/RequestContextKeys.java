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
package com.wikantik.audit;

/**
 * Canonical Log4j2 {@code ThreadContext} (MDC) key names for per-request
 * metadata. Single source of truth shared by the writer
 * ({@code com.wikantik.observability.RequestCorrelationFilter}) and the reader
 * ({@link AuditRequestContext}) so the two cannot drift on a key rename.
 */
public final class RequestContextKeys {

    private RequestContextKeys() {}

    /** Unique per-request correlation id (also surfaced as the {@code X-Request-Id} header). */
    public static final String REQUEST_ID  = "requestId";
    /** HTTP method of the current request. */
    public static final String METHOD      = "method";
    /** Request URI of the current request. */
    public static final String URI         = "uri";
    /** Client IP address of the current request. */
    public static final String REMOTE_ADDR = "remoteAddr";
    /** {@code User-Agent} header of the current request. */
    public static final String USER_AGENT  = "userAgent";
}

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

import org.apache.logging.log4j.ThreadContext;

/**
 * Null-safe reader of per-request metadata from the Log4j2 {@code ThreadContext}
 * (MDC) populated by {@code RequestCorrelationFilter}. Because security events
 * dispatch synchronously on the request thread, audit mapping can read these
 * values here. On non-request threads (schedulers, startup) the MDC is empty and
 * every getter returns {@code null} — the audit columns simply stay blank.
 */
public final class AuditRequestContext {

    private AuditRequestContext() {}

    public static String sourceIp()      { return trimToNull( ThreadContext.get( RequestContextKeys.REMOTE_ADDR ) ); }
    public static String userAgent()     { return trimToNull( ThreadContext.get( RequestContextKeys.USER_AGENT ) ); }
    public static String correlationId() { return trimToNull( ThreadContext.get( RequestContextKeys.REQUEST_ID ) ); }
    public static String uri()           { return trimToNull( ThreadContext.get( RequestContextKeys.URI ) ); }
    public static String method()        { return trimToNull( ThreadContext.get( RequestContextKeys.METHOD ) ); }

    private static String trimToNull( final String s ) {
        return ( s == null || s.isBlank() ) ? null : s;
    }
}

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuditRequestContextTest {

    @BeforeEach
    @AfterEach
    void clearMdc() { ThreadContext.clearAll(); }

    @Test
    void readsValuesFromThreadContext() {
        ThreadContext.put( RequestContextKeys.REMOTE_ADDR, "203.0.113.7" );
        ThreadContext.put( RequestContextKeys.USER_AGENT,  "curl/8.4.0" );
        ThreadContext.put( RequestContextKeys.REQUEST_ID,  "req-123" );
        ThreadContext.put( RequestContextKeys.URI,         "/api/pages/SecretPage" );
        ThreadContext.put( RequestContextKeys.METHOD,      "PUT" );

        assertEquals( "203.0.113.7",           AuditRequestContext.sourceIp() );
        assertEquals( "curl/8.4.0",            AuditRequestContext.userAgent() );
        assertEquals( "req-123",               AuditRequestContext.correlationId() );
        assertEquals( "/api/pages/SecretPage", AuditRequestContext.uri() );
        assertEquals( "PUT",                   AuditRequestContext.method() );
    }

    @Test
    void returnsNullWhenKeysAbsent() {
        assertNull( AuditRequestContext.sourceIp() );
        assertNull( AuditRequestContext.userAgent() );
        assertNull( AuditRequestContext.correlationId() );
        assertNull( AuditRequestContext.uri() );
        assertNull( AuditRequestContext.method() );
    }

    @Test
    void treatsBlankAsNull() {
        ThreadContext.put( RequestContextKeys.REMOTE_ADDR, "   " );
        assertNull( AuditRequestContext.sourceIp() );
    }
}

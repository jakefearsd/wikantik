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
package com.wikantik.work.runtime;

import com.wikantik.WikiEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.audit.AuditCategory;
import com.wikantik.audit.AuditEntry;
import com.wikantik.audit.AuditOutcome;
import com.wikantik.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link WikiWorkAudit}: registry changes as ADMIN entries on the wiki's audit trail, never failing the change. */
class WikiWorkAuditTest {

    @Test
    void recordsAnAdminEntryNamingTheAdminTheActionAndTheTarget() {
        final WikiEngine engine = Mockito.mock( WikiEngine.class );
        final AuditService audit = Mockito.mock( AuditService.class );
        Mockito.when( engine.getAuditService() ).thenReturn( audit );

        new WikiWorkAudit( engine ).record( "janne", "work.agent.update", "work_agent", "codex2", "renamed from codex" );

        final ArgumentCaptor< AuditEntry > e = ArgumentCaptor.forClass( AuditEntry.class );
        Mockito.verify( audit ).record( e.capture() );
        assertEquals( AuditCategory.ADMIN, e.getValue().category() );
        assertEquals( AuditOutcome.SUCCESS, e.getValue().outcome() );
        assertEquals( "janne", e.getValue().actorPrincipal() );
        assertEquals( "work.agent.update", e.getValue().eventType() );
        assertEquals( "work_agent", e.getValue().targetType() );
        assertEquals( "codex2", e.getValue().targetId() );
        assertEquals( "renamed from codex", e.getValue().detail() );
    }

    @Test
    void noDetailIsLeftOut() {
        final WikiEngine engine = Mockito.mock( WikiEngine.class );
        final AuditService audit = Mockito.mock( AuditService.class );
        Mockito.when( engine.getAuditService() ).thenReturn( audit );
        new WikiWorkAudit( engine ).record( "janne", "work.queue.create", "work_queue", "ops", null );
        final ArgumentCaptor< AuditEntry > e = ArgumentCaptor.forClass( AuditEntry.class );
        Mockito.verify( audit ).record( e.capture() );
        assertEquals( "", e.getValue().detail() == null ? "" : e.getValue().detail() );
    }

    @Test
    void anEngineWithoutAnAuditServiceAndAFailingOneAreBothSilentNoOps() {
        assertDoesNotThrow( () -> new WikiWorkAudit( Mockito.mock( Engine.class ) )
            .record( "janne", "work.queue.create", "work_queue", "ops", null ) );
        final WikiEngine noAudit = Mockito.mock( WikiEngine.class );
        assertNull( noAudit.getAuditService() );
        assertDoesNotThrow( () -> new WikiWorkAudit( noAudit ).record( "janne", "a", "b", "c", null ) );
        final WikiEngine failing = Mockito.mock( WikiEngine.class );
        final AuditService broken = Mockito.mock( AuditService.class );
        Mockito.doThrow( new IllegalStateException( "audit queue gone" ) ).when( broken ).record( Mockito.any() );
        Mockito.when( failing.getAuditService() ).thenReturn( broken );
        assertDoesNotThrow( () -> new WikiWorkAudit( failing ).record( "janne", "a", "b", "c", null ) );
    }
}

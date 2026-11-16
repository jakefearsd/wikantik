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
import com.wikantik.work.WorkAudit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;

/** Writes work-registry changes to the wiki's admin audit trail (category ADMIN, outcome SUCCESS). */
public final class WikiWorkAudit implements WorkAudit {

    private static final Logger LOG = LogManager.getLogger( WikiWorkAudit.class );

    private final Engine engine;

    public WikiWorkAudit( final Engine engine ) {
        this.engine = engine;
    }

    @Override
    public void record( final String actorLogin, final String action, final String targetType, final String target,
                        final String detail ) {
        try {
            final AuditService audit = engine instanceof WikiEngine we ? we.getAuditService() : null;
            if ( audit == null ) {
                return;
            }
            final AuditEntry.Builder entry = AuditEntry.builder()
                .eventTime( Instant.now() )
                .category( AuditCategory.ADMIN )
                .eventType( action )
                .outcome( AuditOutcome.SUCCESS )
                .actorPrincipal( actorLogin )
                .actorType( "user" )
                .targetType( targetType )
                .targetId( target )
                .targetLabel( target );
            if ( detail != null ) {
                entry.detail( detail );
            }
            audit.record( entry.build() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Could not record the audit entry {} for {} '{}': {}", action, targetType, target,
                e.getMessage(), e );
        }
    }
}

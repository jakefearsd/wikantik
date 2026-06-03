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

import javax.sql.DataSource;

/** Builds the audit subsystem: JDBC repository, async service, and writer thread. */
public final class AuditSubsystemFactory {

    private AuditSubsystemFactory() {}

    public record AuditSubsystem( AuditService service, AuditWriterThread writer ) {}

    public static AuditSubsystem build( final DataSource dataSource, final int queueCapacity ) {
        final JdbcAuditRepository repo = new JdbcAuditRepository( dataSource );
        final DefaultAuditService service = new DefaultAuditService( repo, queueCapacity );
        final AuditWriterThread writer = new AuditWriterThread( service, repo );
        writer.start();
        return new AuditSubsystem( service, writer );
    }
}

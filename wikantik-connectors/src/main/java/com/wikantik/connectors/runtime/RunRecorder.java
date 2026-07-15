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
package com.wikantik.connectors.runtime;

import com.wikantik.connectors.SyncReport;

/**
 * Seam between {@link ConnectorRuntime} and the run-history store, so unit tests can record
 * (and assert on) run history without a DataSource. {@code JdbcSyncRunStore} (wikantik-connectors
 * state package) implements this directly rather than {@code ConnectorRuntime} declaring it as an
 * inner type — an inner type would force the state package to depend on the runtime package for
 * no reason, and this file is deliberately standalone to avoid that cycle.
 */
public interface RunRecorder {

    /** Record the start of a sync run, returning its runId for the matching finish/fail call. */
    long start( String connectorId, String trigger );

    /** Record a sync run that completed successfully with the given report. */
    void finish( long runId, SyncReport report );

    /** Record a sync run that failed with the given error message. */
    void fail( long runId, String error );
}

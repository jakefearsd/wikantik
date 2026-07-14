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

/** Thrown by {@link ConnectorRuntime#syncNow} when a sync of the same connector is already running
 *  (manual trigger racing the scheduler, or two manual triggers). Mapped to HTTP 409 by the admin
 *  resource; the scheduler skips the connector for that cycle. */
public final class SyncInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SyncInProgressException( final String connectorId ) {
        super( "sync already running for connector '" + connectorId + "'" );
    }
}

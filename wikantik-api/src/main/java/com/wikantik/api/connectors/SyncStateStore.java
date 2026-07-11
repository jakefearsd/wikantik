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
package com.wikantik.api.connectors;

import java.util.List;
import java.util.Optional;

/** Persists per-connector cursors and per-item sync state. Implemented in wikantik-connectors. */
public interface SyncStateStore {
    Optional< SyncCursor > loadCursor( String connectorId );
    void saveCursor( String connectorId, SyncCursor cursor );
    Optional< String > syncedHash( String connectorId, String sourceUri );
    void recordSynced( String connectorId, String sourceUri, String contentHash,
                       String pageName, List< String > aclRefs );
    Optional< String > pageNameFor( String connectorId, String sourceUri );
    List< String > knownUris( String connectorId );
    void removeSynced( String connectorId, String sourceUri );
}

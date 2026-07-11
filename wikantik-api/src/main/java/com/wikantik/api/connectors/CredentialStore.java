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

/** Encrypted-at-rest storage for connector secrets. Disabled (enabled()==false) when no master key is set. */
public interface CredentialStore {
    boolean enabled();
    void put( String connectorId, String name, String secret );
    Optional< String > get( String connectorId, String name );   // decrypted — for connectors, never admin GET
    List< String > list( String connectorId );                   // names only
    void delete( String connectorId, String name );
}

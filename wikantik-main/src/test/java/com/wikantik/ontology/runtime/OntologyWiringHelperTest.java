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
package com.wikantik.ontology.runtime;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wikantik.WikiEngine;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.auth.subsystem.AuthSubsystem;
import org.junit.jupiter.api.Test;

class OntologyWiringHelperTest {

    @Test
    void theEnginesPublicIdentityIsHandedToTheProjection() {
        final WikiEngine engine = mock( WikiEngine.class );
        final PublicIdentity identity = mock( PublicIdentity.class );
        when( engine.getAuthSubsystem() ).thenReturn( new AuthSubsystem.Services(
                null, null, null, null, null, null, null, null, identity ) );

        assertSame( identity, OntologyWiringHelper.publicIdentityOf( engine ) );
    }

    @Test
    void anEngineWithoutAnAuthSubsystemProjectsAuthorsAsWritten() {
        assertNull( OntologyWiringHelper.publicIdentityOf( mock( WikiEngine.class ) ) );
    }

    @Test
    void anAuthSubsystemWithoutAnIdentityProjectsAuthorsAsWritten() {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.getAuthSubsystem() ).thenReturn( new AuthSubsystem.Services(
                null, null, null, null, null, null, null, null, null ) );

        assertNull( OntologyWiringHelper.publicIdentityOf( engine ) );
    }
}

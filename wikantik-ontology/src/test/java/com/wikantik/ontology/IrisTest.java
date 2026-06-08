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
package com.wikantik.ontology;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IrisTest {

    @Test
    void termIriUsesWkNamespace() {
        assertEquals( "https://wiki.wikantik.com/ns/wikantik#Technology", Iris.term( "Technology" ) );
    }

    @Test
    void entityIriUsesUuid() {
        final UUID id = UUID.fromString( "00000000-0000-0000-0000-0000000000ab" );
        assertEquals( "https://wiki.wikantik.com/id/entity/00000000-0000-0000-0000-0000000000ab",
                Iris.entity( id ) );
    }

    @Test
    void pageIriUsesCanonicalId() {
        assertEquals( "https://wiki.wikantik.com/id/page/01KTGSV4B1PR4RBEGQGBJVNXN0",
                Iris.page( "01KTGSV4B1PR4RBEGQGBJVNXN0" ) );
    }

    @Test
    void conceptIriIsSanitizedLowercaseWithHyphens() {
        assertEquals( "https://wiki.wikantik.com/id/concept/low-cost-index-funds",
                Iris.concept( "Low Cost Index Funds" ) );
    }

    @Test
    void conceptIriKeepsSlashForSubClusterPaths() {
        assertEquals( "https://wiki.wikantik.com/id/concept/retirement-planning/eu-retirement",
                Iris.concept( "retirement-planning/eu-retirement" ) );
    }
}

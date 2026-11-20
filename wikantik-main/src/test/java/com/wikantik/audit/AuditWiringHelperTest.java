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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Set;

class AuditWiringHelperTest {

    @Test
    void testParseAuditedClusters_nullOrBlank_returnsEmptySet() {
        Assertions.assertTrue( AuditWiringHelper.parseAuditedClusters( null ).isEmpty() );
        Assertions.assertTrue( AuditWiringHelper.parseAuditedClusters( "" ).isEmpty() );
        Assertions.assertTrue( AuditWiringHelper.parseAuditedClusters( "   " ).isEmpty() );
    }

    @Test
    void testParseAuditedClusters_trimsAndDropsEmptyEntries() {
        Assertions.assertEquals( Set.of( "engineering", "finance" ),
            AuditWiringHelper.parseAuditedClusters( " engineering , , finance ,engineering" ) );
    }

    @Test
    void testWireAudit_noDataSource_returnsNull() {
        Assertions.assertNull( AuditWiringHelper.wireAudit( new Properties(), null,
            new AuditWiringHelper.Managers( null, null, null, null, null, null, null, null ) ) );
    }
}

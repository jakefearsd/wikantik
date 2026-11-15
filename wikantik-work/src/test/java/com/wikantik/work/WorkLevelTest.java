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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkLevelTest {

    @Test
    void hierarchyIsEpicStoryTask() {
        assertTrue( WorkLevel.EPIC.canParent( WorkLevel.STORY ) );
        assertTrue( WorkLevel.STORY.canParent( WorkLevel.TASK ) );
        assertFalse( WorkLevel.EPIC.canParent( WorkLevel.TASK ) );
        assertFalse( WorkLevel.TASK.canParent( WorkLevel.TASK ) );
        assertFalse( WorkLevel.STORY.canParent( WorkLevel.EPIC ) );
    }

    @Test
    void otherEnumsUseLowercaseWire() {
        assertEquals( "epic", WorkLevel.EPIC.wire() );
        assertEquals( "software", WorkKind.SOFTWARE.wire() );
        assertEquals( "delegated", Autonomy.DELEGATED.wire() );
        assertEquals( "in_production", DoneMeans.IN_PRODUCTION.wire() );
        assertEquals( "human", DeployGate.HUMAN.wire() );
        assertEquals( WorkRole.LEAD, WorkRole.fromWire( "lead" ) );
        assertThrows( WorkValidationException.class, () -> WorkKind.fromWire( "x" ) );
    }

    @Test
    void rolesOrderByPrivilege() {
        assertTrue( WorkRole.VIEWER.compareTo( WorkRole.MEMBER ) < 0 );
        assertTrue( WorkRole.MEMBER.compareTo( WorkRole.LEAD ) < 0 );
        assertEquals( WorkRole.LEAD, WorkRole.highest( java.util.List.of( WorkRole.VIEWER, WorkRole.LEAD, WorkRole.MEMBER ) ) );
        assertEquals( null, WorkRole.highest( java.util.List.of() ) );
    }
}

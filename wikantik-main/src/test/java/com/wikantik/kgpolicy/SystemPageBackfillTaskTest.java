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
package com.wikantik.kgpolicy;

import com.wikantik.api.kgpolicy.ExclusionReason;
import com.wikantik.api.managers.SystemPageRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemPageBackfillTaskTest {

    @Test
    void marks_each_system_page() {
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.getSystemPageNames() ).thenReturn( Set.of( "Sandbox", "About" ) );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );

        final SystemPageBackfillTask t = new SystemPageBackfillTask( sys, repo );
        assertEquals( 2, t.run() );

        verify( repo ).exclude( "Sandbox", ExclusionReason.SYSTEM_PAGE );
        verify( repo ).exclude( "About",   ExclusionReason.SYSTEM_PAGE );
    }

    @Test
    void zero_when_registry_is_empty() {
        final SystemPageRegistry sys = mock( SystemPageRegistry.class );
        when( sys.getSystemPageNames() ).thenReturn( Set.of() );
        final KgExcludedPagesRepository repo = mock( KgExcludedPagesRepository.class );

        assertEquals( 0, new SystemPageBackfillTask( sys, repo ).run() );
        verifyNoInteractions( repo );
    }
}

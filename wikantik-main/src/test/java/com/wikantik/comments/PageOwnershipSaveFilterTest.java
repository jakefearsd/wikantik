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
package com.wikantik.comments;

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PageOwnershipSaveFilterTest {

    private PageOwnerService pageOwners;
    private Context ctx;
    private Page page;

    @BeforeEach
    void setUp() {
        pageOwners = mock( PageOwnerService.class );
        ctx = mock( Context.class );
        page = mock( Page.class );
        when( page.getName() ).thenReturn( "MyPage" );
        when( ctx.getPage() ).thenReturn( page );
    }

    @Test
    void postSave_invokes_getOwner_when_enabled_and_canonical_id_resolves() throws Exception {
        final var filter = new PageOwnershipSaveFilter(
                pageOwners,
                slug -> Optional.of( "01J0CANON" ),
                true );
        filter.postSave( ctx, "body" );
        verify( pageOwners ).getOwner( eq( "01J0CANON" ) );
        verifyNoMoreInteractions( pageOwners );
    }

    @Test
    void postSave_is_a_noop_when_disabled() throws Exception {
        final var filter = new PageOwnershipSaveFilter(
                pageOwners,
                slug -> Optional.of( "01J0CANON" ),
                false );
        filter.postSave( ctx, "body" );
        verifyNoInteractions( pageOwners );
    }

    @Test
    void postSave_is_a_noop_when_canonical_id_does_not_resolve() throws Exception {
        final var filter = new PageOwnershipSaveFilter(
                pageOwners,
                slug -> Optional.empty(),
                true );
        filter.postSave( ctx, "body" );
        verifyNoInteractions( pageOwners );
    }

    @Test
    void postSave_swallows_runtime_errors_from_getOwner() throws Exception {
        when( pageOwners.getOwner( anyString() ) ).thenThrow( new RuntimeException( "db down" ) );
        final var filter = new PageOwnershipSaveFilter(
                pageOwners,
                slug -> Optional.of( "01J0CANON" ),
                true );
        // postSave never throws — ownership tracking must never block a save.
        assertDoesNotThrow( () -> filter.postSave( ctx, "body" ) );
        verify( pageOwners ).getOwner( eq( "01J0CANON" ) );
    }

    @Test
    void postSave_handles_null_page_in_context() throws Exception {
        when( ctx.getPage() ).thenReturn( null );
        final var filter = new PageOwnershipSaveFilter(
                pageOwners,
                slug -> Optional.of( "01J0CANON" ),
                true );
        assertDoesNotThrow( () -> filter.postSave( ctx, "body" ) );
        verifyNoInteractions( pageOwners );
    }
}

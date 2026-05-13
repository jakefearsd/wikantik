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
package com.wikantik.knowledge.curation;

import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class DefaultKgCurationOpsTest {

    private KnowledgeGraphService kg;
    private PageManager pages;
    private PageSaveHelper saver;
    private DefaultKgCurationOps ops;

    @BeforeEach
    void setUp() {
        kg = Mockito.mock( KnowledgeGraphService.class );
        pages = Mockito.mock( PageManager.class );
        saver = Mockito.mock( PageSaveHelper.class );
        ops = new DefaultKgCurationOps( kg, pages, saver );
    }

    @Test
    void approveReturnsEmptyOptionalOnSuccess() {
        final UUID id = UUID.randomUUID();
        final KgProposal approved = Mockito.mock( KgProposal.class );
        when( approved.proposalType() ).thenReturn( "new-node" );
        when( kg.approveProposal( eq( id ), eq( "alice" ) ) ).thenReturn( approved );

        assertEquals( Optional.empty(), ops.tryApproveProposal( id, "alice" ) );
    }

    @Test
    void approveReturnsNotFoundWhenServiceReturnsNull() {
        final UUID id = UUID.randomUUID();
        when( kg.approveProposal( eq( id ), eq( "alice" ) ) ).thenReturn( null );

        final Optional<String> result = ops.tryApproveProposal( id, "alice" );
        assertTrue( result.isPresent() );
        assertTrue( result.get().contains( "Not found" ) );
    }

    @Test
    void approveSurfacesServiceExceptionMessage() {
        final UUID id = UUID.randomUUID();
        when( kg.approveProposal( eq( id ), any() ) )
                .thenThrow( new RuntimeException( "constraint violation" ) );

        final Optional<String> result = ops.tryApproveProposal( id, "alice" );
        assertTrue( result.isPresent() );
        assertEquals( "constraint violation", result.get() );
    }
}

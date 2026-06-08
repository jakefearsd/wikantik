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

import static org.mockito.Mockito.verify;

import com.wikantik.event.WikiPageEvent;
import com.wikantik.event.WikiPageRenameEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith( MockitoExtension.class )
class OntologyEventListenerTest {

    @Mock OntologyPageSync sync;

    @Test
    void postSaveEndRoutesToOnPageSaved() {
        new OntologyEventListener( sync ).actionPerformed(
                new WikiPageEvent( this, WikiPageEvent.POST_SAVE_END, "Foo" ) );
        verify( sync ).onPageSaved( "Foo" );
    }

    @Test
    void pageDeletedRoutesToOnPageDeleted() {
        new OntologyEventListener( sync ).actionPerformed(
                new WikiPageEvent( this, WikiPageEvent.PAGE_DELETED, "Foo" ) );
        verify( sync ).onPageDeleted( "Foo" );
    }

    @Test
    void renameRoutesToOnPageRenamed() {
        new OntologyEventListener( sync ).actionPerformed(
                new WikiPageRenameEvent( this, "Old", "New" ) );
        verify( sync ).onPageRenamed( "Old", "New" );
    }
}

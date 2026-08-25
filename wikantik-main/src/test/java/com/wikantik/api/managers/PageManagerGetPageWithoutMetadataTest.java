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
package com.wikantik.api.managers;

import com.wikantik.api.core.Page;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@link PageManager#getPageWithoutMetadata(String, int)} default method:
 * a third-party implementation or test mock that has never heard of it must still behave
 * correctly by delegating straight to {@link PageManager#getPage(String, int)}.
 */
class PageManagerGetPageWithoutMetadataTest {

    @Test
    void defaultGetPageWithoutMetadataDelegatesToGetPage() {
        // CALLS_REAL_METHODS lets the interface's default method actually execute instead of
        // being stubbed to return null, so this exercises the real delegation.
        final PageManager pm = Mockito.mock( PageManager.class, Mockito.CALLS_REAL_METHODS );
        final Page page = Mockito.mock( Page.class );
        when( pm.getPage( "Foo", 3 ) ).thenReturn( page );

        final Page result = pm.getPageWithoutMetadata( "Foo", 3 );

        assertSame( page, result, "default getPageWithoutMetadata must return whatever getPage(name, version) returns" );
        verify( pm ).getPage( "Foo", 3 );
    }
}

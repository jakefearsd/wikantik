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
package com.wikantik.filters;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Engine;
import com.wikantik.api.filters.PageFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine shutdown destroys every page filter; the reference manager flushes its pending database write there. One
 * filter throwing from {@code destroy} must not skip the filters after it.
 */
class DefaultFilterManagerDestroyTest {

    private final TestEngine engine = TestEngine.build();

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void aFilterThatThrowsOnDestroyDoesNotSkipTheRest() {
        final FilterManager fm = engine.getManager( FilterManager.class );
        final AtomicBoolean laterDestroyed = new AtomicBoolean();
        fm.addPageFilter( new PageFilter() {
            @Override
            public void destroy( final Engine e ) {
                throw new IllegalStateException( "boom on destroy" );
            }
        }, 10_000 );
        fm.addPageFilter( new PageFilter() {
            @Override
            public void destroy( final Engine e ) {
                laterDestroyed.set( true );
            }
        }, -10_000 );

        fm.destroy();

        assertTrue( laterDestroyed.get(), "every filter is destroyed even when an earlier one throws" );
    }
}

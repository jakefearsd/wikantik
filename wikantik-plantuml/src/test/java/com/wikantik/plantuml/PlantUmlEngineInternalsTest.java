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
package com.wikantik.plantuml;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure-logic corners of {@link PlantUmlEngine} that a real PlantUML run cannot place precisely. */
class PlantUmlEngineInternalsTest {

    private static int toAuthorLine( final int insertedAfterLine, final int insertedLines, final int processedLine ) throws Exception {
        final Class< ? > type = Class.forName( PlantUmlEngine.class.getName() + "$Prepared" );
        final Constructor< ? > ctor = type.getDeclaredConstructor( String.class, int.class, int.class );
        ctor.setAccessible( true );
        final Object prepared = ctor.newInstance( "", insertedAfterLine, insertedLines );
        final Method m = type.getDeclaredMethod( "toAuthorLine", int.class );
        m.setAccessible( true );
        return (int) m.invoke( prepared, processedLine );
    }

    @Test
    void linesAtOrBeforeTheInjectionPointMapToThemselves() throws Exception {
        assertEquals( 1, toAuthorLine( 1, 1, 1 ) );
        assertEquals( 3, toAuthorLine( 5, 1, 3 ) );
    }

    @Test
    void linesAfterTheInjectionShiftBackByTheInjectedCount() throws Exception {
        assertEquals( 2, toAuthorLine( 1, 1, 3 ) );
        assertEquals( 1, toAuthorLine( 0, 2, 3 ) );
    }

    @Test
    void anErrorInsideTheInjectedTextHasNoAuthorLine() throws Exception {
        assertEquals( 0, toAuthorLine( 1, 1, 2 ) );
        assertEquals( 0, toAuthorLine( 0, 2, 2 ) );
        assertEquals( 0, toAuthorLine( 0, 2, 1 ) );
    }

    @Test
    void oneCrashMarkerAloneIsNotACrash() {
        assertNull( PlantUmlEngine.crashOutcome( new PlantUmlEngine.Export( "<svg>An error has occurred : x</svg>", 0 ) ) );
        assertNull( PlantUmlEngine.crashOutcome( new PlantUmlEngine.Export( "<svg>the thing has crashed.</svg>", 0 ) ) );
    }

    @Test
    void anyNonZeroStatusIsACrashEvenForHealthyLookingSvg() {
        assertEquals( "render-crashed", PlantUmlEngine.crashOutcome( new PlantUmlEngine.Export( "<svg/>", 1 ) ).reason() );
        assertEquals( "render-crashed", PlantUmlEngine.crashOutcome( new PlantUmlEngine.Export( "<svg/>", -1 ) ).reason() );
    }

    @Test
    void stackOverflowCrashTextIsTooComplexRegardlessOfStatus() {
        final RenderOutcome out = PlantUmlEngine.crashOutcome( new PlantUmlEngine.Export( "<svg>StackOverflowError</svg>", 503 ) );
        assertEquals( "too-complex", out.reason() );
        assertEquals( false, out.transientFailure() );
    }
}

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static com.wikantik.work.PolicyItems.chain;
import static com.wikantik.work.PolicyItems.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkAutonomyTest {

    private static Autonomy level( final String wire ) {
        return "-".equals( wire ) ? null : Autonomy.fromWire( wire );
    }

    /** own, story, epic, project default, product ceiling ("-" = not set / no product) -> effective, inherited. */
    @ParameterizedTest( name = "own={0} story={1} epic={2} project={3} ceiling={4} -> {5}" )
    @CsvSource( {
        "-,-,-,delegated,-,delegated,delegated",
        "-,-,-,supervised,-,supervised,supervised",
        "supervised,-,autonomous,delegated,-,supervised,autonomous",
        "autonomous,-,supervised,delegated,-,autonomous,supervised",
        "-,supervised,autonomous,delegated,-,supervised,supervised",
        "-,-,autonomous,delegated,-,autonomous,autonomous",
        "-,-,autonomous,delegated,delegated,delegated,autonomous",
        "autonomous,-,-,delegated,supervised,supervised,delegated",
        "supervised,-,-,delegated,autonomous,supervised,delegated",
        "-,-,-,delegated,autonomous,delegated,delegated",
        "-,-,-,autonomous,delegated,delegated,autonomous"
    } )
    void levelResolution( final String own, final String story, final String epic, final String project,
                          final String ceiling, final String effective, final String inherited ) {
        final List< Item > ancestors = new ArrayList<>();
        ancestors.add( item( WorkLevel.STORY, level( story ), null, null ) );
        ancestors.add( item( WorkLevel.EPIC, level( epic ), null, null ) );
        final PolicyChain chain = chain( PolicyItems.task( level( own ) ), ancestors, level( project ),
            level( ceiling ) );
        assertEquals( level( effective ), WorkAutonomy.resolve( chain ).level() );
        assertEquals( level( inherited ), WorkAutonomy.inherited( chain ) );
    }

    @Test
    void anItemWithNoAncestorsUsesTheProjectDefault() {
        assertEquals( Autonomy.SUPERVISED, WorkAutonomy.resolve(
            chain( PolicyItems.task( null ), List.of(), Autonomy.SUPERVISED, null ) ).level() );
    }

    @Test
    void allowDirectInheritsFromTheEpicUnlessTheItemSaysOtherwise() {
        final Item epicOn = item( WorkLevel.EPIC, null, true, null );
        final Item story = item( WorkLevel.STORY, null, null, null );
        assertTrue( WorkAutonomy.resolve( chain( item( WorkLevel.TASK, null, null, null ), List.of( story, epicOn ),
            Autonomy.DELEGATED, null ) ).directAllowed() );
        assertFalse( WorkAutonomy.resolve( chain( item( WorkLevel.TASK, null, false, null ), List.of( story, epicOn ),
            Autonomy.DELEGATED, null ) ).directAllowed() );
        assertTrue( WorkAutonomy.resolve( chain( item( WorkLevel.TASK, null, true, null ), List.of(),
            Autonomy.DELEGATED, null ) ).directAllowed() );
        assertFalse( WorkAutonomy.resolve( chain( item( WorkLevel.TASK, null, null, null ), List.of(),
            Autonomy.DELEGATED, null ) ).directAllowed(), "off unless a lead allowed it" );
        final Item storyOff = item( WorkLevel.STORY, null, false, null );
        assertFalse( WorkAutonomy.resolve( chain( item( WorkLevel.TASK, null, null, null ),
            List.of( storyOff, epicOn ), Autonomy.DELEGATED, null ) ).directAllowed(), "the nearest value wins" );
    }

    @Test
    void doneMeansComesFromTheEpicOnly() {
        final Item epicProd = item( WorkLevel.EPIC, null, null, DoneMeans.IN_PRODUCTION );
        final Item epicMerged = item( WorkLevel.EPIC, null, null, DoneMeans.MERGED );
        final Item storyProd = item( WorkLevel.STORY, null, null, DoneMeans.IN_PRODUCTION );
        assertEquals( DoneMeans.IN_PRODUCTION, WorkAutonomy.resolve( chain( PolicyItems.task( null ),
            List.of( item( WorkLevel.STORY, null, null, null ), epicProd ), Autonomy.DELEGATED, null ) ).doneWhen() );
        assertEquals( DoneMeans.MERGED, WorkAutonomy.resolve( chain( storyProd, List.of( epicMerged ),
            Autonomy.DELEGATED, null ) ).doneWhen(), "a story's own value is ignored" );
        assertEquals( DoneMeans.MERGED, WorkAutonomy.resolve( chain( storyProd, List.of(),
            Autonomy.DELEGATED, null ) ).doneWhen(), "no epic, so merged" );
        assertEquals( DoneMeans.IN_PRODUCTION, WorkAutonomy.resolve( chain( epicProd, List.of(),
            Autonomy.DELEGATED, null ) ).doneWhen(), "an epic's own value is its setting" );
        assertEquals( DoneMeans.MERGED, WorkAutonomy.resolve( chain( item( WorkLevel.EPIC, null, null, null ),
            List.of(), Autonomy.DELEGATED, null ) ).doneWhen() );
    }

    @Test
    void strictestTreatsNullAsNoLimit() {
        assertEquals( Autonomy.DELEGATED, Autonomy.strictest( null, Autonomy.DELEGATED ) );
        assertEquals( Autonomy.SUPERVISED, Autonomy.strictest( Autonomy.SUPERVISED, Autonomy.AUTONOMOUS ) );
        assertTrue( Autonomy.AUTONOMOUS.looserThan( Autonomy.DELEGATED ) );
        assertFalse( Autonomy.DELEGATED.looserThan( Autonomy.DELEGATED ) );
    }
}

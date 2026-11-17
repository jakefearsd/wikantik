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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Who may set an item's autonomy, direct-commit and done-means fields. */
@RequiresPostgres
class WorkPolicyFieldsPostgresTest {

    private static final String LOOSER = "Making this looser than it inherits takes a project lead.";
    private static final String PIN = "Setting the level an item already inherits takes a project lead.";
    private static final String MOVE = "Moving this to where agents may do more takes a project lead.";

    private GateFixture f;
    private Item epic;
    private Item story;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
        epic = f.draft( WorkLevel.EPIC, null );
        story = f.draft( WorkLevel.STORY, epic.key() );
    }

    private String denied( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkDeniedException.class, call ).reason();
    }

    @Test
    void aMemberMakesAStoryStricterThanItsEpic() throws SQLException {
        assertEquals( Autonomy.SUPERVISED, f.set( f.mia, story, "autonomy", "supervised" ).autonomy() );
    }

    @Test
    void aMemberCannotMakeItLooser() throws SQLException {
        f.level( epic, Autonomy.SUPERVISED );
        assertEquals( LOOSER, denied( () -> f.set( f.mia, story, "autonomy", "delegated" ) ) );
        assertNull( f.fresh( story ).autonomy() );
    }

    @Test
    void aMemberCannotPinTheLevelAnItemAlreadyInherits() throws SQLException {
        assertEquals( PIN, denied( () -> f.set( f.mia, story, "autonomy", "delegated" ) ),
            "an own value would outlive a lead tightening the epic" );
        assertNull( f.fresh( story ).autonomy() );
    }

    @Test
    void onlyALeadSetsAutonomous() throws SQLException {
        f.level( epic, Autonomy.AUTONOMOUS );
        assertEquals( "This action needs the lead role; you hold member.",
            denied( () -> f.set( f.mia, story, "autonomy", "autonomous" ) ), "even where the epic already is" );
        assertEquals( Autonomy.AUTONOMOUS, f.set( f.lena, story, "autonomy", "autonomous" ).autonomy() );
    }

    @Test
    void aMemberClearsALevelALeadLoosened() throws SQLException {
        f.set( f.lena, story, "autonomy", "autonomous" );
        assertNull( f.set( f.mia, story, "autonomy", null ).autonomy(), "back to the stricter level it inherits" );
    }

    @Test
    void movingAnItemWhereAgentsMayDoMoreTakesALead() throws SQLException {
        f.level( epic, Autonomy.SUPERVISED );
        final Item loose = f.draft( WorkLevel.EPIC, null );
        f.level( loose, Autonomy.AUTONOMOUS );
        assertEquals( MOVE, denied( () -> f.set( f.mia, story, "parentKey", loose.key() ) ) );
        assertEquals( "An agent may not change policy, manage a project or manage the work instance.",
            denied( () -> f.set( f.author, story, "parentKey", loose.key() ) ) );
        assertEquals( loose.id(), f.set( f.lena, story, "parentKey", loose.key() ).parentId() );
        assertEquals( epic.id(), f.set( f.mia, story, "parentKey", epic.key() ).parentId(), "to a stricter epic is free" );
    }

    @Test
    void retargetingToAProductWithAHigherCeilingTakesALead() throws SQLException {
        f.svc.createProduct( f.admin, new Product( 0, "strict", "Strict", null, "main", null, Autonomy.SUPERVISED,
            DeployGate.HUMAN, null ) );
        assertEquals( "strict", f.svc.item( f.lena, f.set( f.mia, story, "productKey", "strict" ).key() ).productKey() );
        assertEquals( MOVE, denied( () -> f.set( f.mia, story, "productKey", "wikantik" ) ) );
        assertEquals( MOVE, denied( () -> f.set( f.mia, story, "productKey", null ) ), "no product, no ceiling" );
        assertEquals( "wikantik", f.svc.item( f.lena, f.set( f.lena, story, "productKey", "wikantik" ).key() ).productKey() );
    }

    @Test
    void aLeadMakesItLooser() throws SQLException {
        f.level( epic, Autonomy.SUPERVISED );
        assertEquals( Autonomy.DELEGATED, f.set( f.lena, story, "autonomy", "delegated" ).autonomy() );
        assertEquals( Autonomy.AUTONOMOUS, f.set( f.lena, story, "autonomy", "autonomous" ).autonomy() );
    }

    @Test
    void aMemberTightensWhatALeadLoosened() throws SQLException {
        f.set( f.lena, story, "autonomy", "autonomous" );
        assertEquals( Autonomy.DELEGATED, f.set( f.mia, story, "autonomy", "delegated" ).autonomy() );
    }

    @Test
    void clearingAStricterValueIsALoosening() throws SQLException {
        f.set( f.lena, story, "autonomy", "supervised" );
        assertEquals( LOOSER, denied( () -> f.set( f.mia, story, "autonomy", null ) ) );
        assertNull( f.set( f.lena, story, "autonomy", null ).autonomy() );
    }

    @Test
    void onlyALeadSetsAllowDirectOrDoneMeans() throws SQLException {
        assertEquals( "This action needs the lead role; you hold member.",
            denied( () -> f.set( f.mia, epic, "allowDirect", true ) ) );
        assertEquals( "This action needs the lead role; you hold member.",
            denied( () -> f.set( f.mia, epic, "doneMeans", "in_production" ) ) );
        assertEquals( "This action needs the lead role; you hold member.",
            denied( () -> f.set( f.mia, epic, "allowDirect", false ) ), "turning it off is a lead's call too" );
        assertEquals( Boolean.TRUE, f.set( f.lena, epic, "allowDirect", true ).allowDirect() );
        assertEquals( DoneMeans.IN_PRODUCTION, f.set( f.lena, epic, "doneMeans", "in_production" ).doneMeans() );
    }

    @Test
    void anAgentSetsNoPolicyField() {
        final String refusal = "An agent may not change policy, manage a project or manage the work instance.";
        assertEquals( refusal, denied( () -> f.set( f.author, story, "autonomy", "supervised" ) ) );
        assertEquals( refusal, denied( () -> f.set( f.author, story, "allowDirect", false ) ) );
    }
}

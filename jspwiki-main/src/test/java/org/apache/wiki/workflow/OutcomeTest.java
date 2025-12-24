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
package org.apache.wiki.workflow;

import org.apache.wiki.TestEngine;
import org.apache.wiki.WikiEngine;
import org.apache.wiki.i18n.InternationalizationManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Locale;

public class OutcomeTest {

    @Test
    public void testGetKey() {
        Assertions.assertEquals( "outcome.decision.approve", Outcome.DECISION_APPROVE.getMessageKey() );
        Assertions.assertEquals( "outcome.decision.hold", Outcome.DECISION_HOLD.getMessageKey() );
        Assertions.assertEquals( "outcome.decision.deny", Outcome.DECISION_DENY.getMessageKey() );
        Assertions.assertEquals( "outcome.decision.reassign", Outcome.DECISION_REASSIGN.getMessageKey() );
    }

    @Test
    public void testHashCode() {
        Assertions.assertEquals( "outcome.decision.approve".hashCode(), Outcome.DECISION_APPROVE.hashCode() );
        Assertions.assertEquals( "outcome.decision.hold".hashCode() * 2, Outcome.DECISION_HOLD.hashCode() );
        Assertions.assertEquals( "outcome.decision.deny".hashCode(), Outcome.DECISION_DENY.hashCode() );
        Assertions.assertEquals( "outcome.decision.reassign".hashCode() * 2, Outcome.DECISION_REASSIGN.hashCode() );
    }

    @Test
    public void testEquals() {
        Assertions.assertEquals( Outcome.DECISION_APPROVE, Outcome.DECISION_APPROVE );
        Assertions.assertNotSame( Outcome.DECISION_APPROVE, Outcome.DECISION_REASSIGN );
    }

    @Test
    public void testMessage() {
        final WikiEngine engine = TestEngine.build();
        final InternationalizationManager i18n = engine.getManager( InternationalizationManager.class );
        final String core = "templates.default";
        final Locale rootLocale = Locale.ROOT;
        Outcome o;

        o = Outcome.DECISION_APPROVE;
        Assertions.assertEquals("Approve", i18n.get(core, rootLocale, o.getMessageKey()));

        o = Outcome.DECISION_DENY;
        Assertions.assertEquals("Deny", i18n.get(core, rootLocale, o.getMessageKey()));

        o = Outcome.DECISION_HOLD;
        Assertions.assertEquals("Hold", i18n.get(core, rootLocale, o.getMessageKey()));

        o = Outcome.DECISION_REASSIGN;
        Assertions.assertEquals("Reassign", i18n.get(core, rootLocale, o.getMessageKey()));
    }

    @Test
    public void testIsCompletion() {
        Assertions.assertTrue(Outcome.DECISION_ACKNOWLEDGE.isCompletion());
        Assertions.assertTrue(Outcome.DECISION_APPROVE.isCompletion());
        Assertions.assertTrue(Outcome.DECISION_DENY.isCompletion());
        Assertions.assertFalse(Outcome.DECISION_HOLD.isCompletion());
        Assertions.assertFalse(Outcome.DECISION_REASSIGN.isCompletion());
        Assertions.assertTrue(Outcome.STEP_ABORT.isCompletion());
        Assertions.assertTrue(Outcome.STEP_COMPLETE.isCompletion());
        Assertions.assertFalse(Outcome.STEP_CONTINUE.isCompletion());
    }

    @Test
    public void testForName() {
        try {
            Assertions.assertEquals( Outcome.DECISION_ACKNOWLEDGE, Outcome.forName( "outcome.decision.acknowledge" ) );
            Assertions.assertEquals( Outcome.DECISION_APPROVE, Outcome.forName( "outcome.decision.approve" ) );
            Assertions.assertEquals( Outcome.DECISION_DENY, Outcome.forName( "outcome.decision.deny" ) );
            Assertions.assertEquals( Outcome.DECISION_HOLD, Outcome.forName( "outcome.decision.hold" ) );
            Assertions.assertEquals( Outcome.DECISION_REASSIGN, Outcome.forName( "outcome.decision.reassign" ) );
            Assertions.assertEquals( Outcome.STEP_ABORT, Outcome.forName( "outcome.step.abort" ) );
            Assertions.assertEquals( Outcome.STEP_COMPLETE, Outcome.forName( "outcome.step.complete" ) );
            Assertions.assertEquals( Outcome.STEP_CONTINUE, Outcome.forName( "outcome.step.continue" ) );
        } catch( final NoSuchOutcomeException e ) {
            // We should never get here
            Assertions.fail( "Could not look up an Outcome..." );
        }

        // Look for a non-existent one
        try {
            Outcome.forName( "outcome.decision.nonexistent" );
        } catch( final NoSuchOutcomeException e ) {
            return;
        }
        // We should never get here
        Assertions.fail( "Could not look up an Outcome..." );
    }

    @Test
    public void testSerializationResolvesSingleton() throws Exception {
        // Serialize and deserialize each Outcome
        final Outcome[] outcomes = {
            Outcome.STEP_COMPLETE, Outcome.STEP_ABORT, Outcome.STEP_CONTINUE,
            Outcome.DECISION_ACKNOWLEDGE, Outcome.DECISION_APPROVE,
            Outcome.DECISION_DENY, Outcome.DECISION_HOLD, Outcome.DECISION_REASSIGN
        };

        for ( final Outcome original : outcomes ) {
            // Serialize
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try ( final ObjectOutputStream oos = new ObjectOutputStream( baos ) ) {
                oos.writeObject( original );
            }

            // Deserialize
            final ByteArrayInputStream bais = new ByteArrayInputStream( baos.toByteArray() );
            final Outcome deserialized;
            try ( final ObjectInputStream ois = new ObjectInputStream( bais ) ) {
                deserialized = (Outcome) ois.readObject();
            }

            // Should be the exact same singleton instance (not just equal)
            Assertions.assertSame( original, deserialized,
                "Deserialized " + original.getMessageKey() + " should be same instance as original singleton" );
        }
    }

    /**
     * Tests that the hashCode method works correctly after deserialization.
     * This is important because HashMap uses hashCode during readObject,
     * which happens before readResolve can fix singleton identity.
     * The readObject method must ensure key is populated before hashCode is called.
     */
    @Test
    public void testDeserializedHashCodeBeforeResolve() throws Exception {
        // This test verifies that hashCode works on deserialized objects.
        // The fix for backward compatibility ensures that key is never null
        // after readObject completes, preventing NPE in hashCode.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try ( final ObjectOutputStream oos = new ObjectOutputStream( baos ) ) {
            oos.writeObject( Outcome.DECISION_APPROVE );
        }

        final ByteArrayInputStream bais = new ByteArrayInputStream( baos.toByteArray() );
        try ( final ObjectInputStream ois = new ObjectInputStream( bais ) ) {
            final Outcome deserialized = (Outcome) ois.readObject();
            // hashCode should work without throwing NPE
            Assertions.assertEquals( Outcome.DECISION_APPROVE.hashCode(), deserialized.hashCode() );
        }
    }

}


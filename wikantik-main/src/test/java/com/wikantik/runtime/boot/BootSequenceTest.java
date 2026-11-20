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
package com.wikantik.runtime.boot;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootSequenceTest {

    private static final WiringEnv ENV =
        new WiringEnv( new Properties(), null, null, null, null, null, null, null, null, null );

    private final List< String > started = new ArrayList<>();
    private final List< String > sequenceLog = new ArrayList<>();
    private AbstractAppender appender;

    @BeforeEach
    void captureSequenceLog() {
        final String name = BootSequence.class.getName();
        appender = new AbstractAppender( "bootSequenceTest", null, null, true, Property.EMPTY_ARRAY ) {
            @Override public void append( final LogEvent event ) {
                sequenceLog.add( event.getLevel() + " " + event.getMessage().getFormattedMessage() );
            }
        };
        appender.start();
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        final Configuration config = ctx.getConfiguration();
        final LoggerConfig lc = LoggerConfig.newBuilder().setAdditivity( false ).setLevel( Level.ALL )
            .setLoggerName( name ).setIncludeLocation( "false" ).setRefs( new AppenderRef[ 0 ] ).setConfig( config ).build();
        lc.addAppender( appender, Level.ALL, null );
        config.addLogger( name, lc );
        ctx.updateLoggers();
    }

    @AfterEach
    void releaseSequenceLog() {
        final LoggerContext ctx = ( LoggerContext ) LogManager.getContext( false );
        ctx.getConfiguration().removeLogger( BootSequence.class.getName() );
        ctx.updateLoggers();
        appender.stop();
    }

    private BootStep recording( final String name, final FailurePolicy policy ) {
        return policy == FailurePolicy.ABORT_REST
            ? BootStep.abortRestOnFailure( name, env -> started.add( name ) )
            : BootStep.continueOnFailure( name, env -> started.add( name ), e -> started.add( "report " + name ) );
    }

    /** A hand-written step that throws, relying on the interface's default failure report. */
    private static BootStep throwing( final String name, final FailurePolicy policy, final Exception failure ) {
        return new BootStep() {
            @Override public String name() { return name; }
            @Override public FailurePolicy policy() { return policy; }
            @Override public void start( final WiringEnv env ) throws Exception { throw failure; }
        };
    }

    @Test
    void stepsStartInDeclaredOrderWithTheSharedEnv() {
        final List< WiringEnv > seen = new ArrayList<>();
        new BootSequence( List.of(
            recording( "a", FailurePolicy.ABORT_REST ),
            BootStep.abortRestOnFailure( "b", env -> { seen.add( env ); started.add( "b" ); } ),
            recording( "c", FailurePolicy.CONTINUE ) ) ).start( ENV );

        assertEquals( List.of( "a", "b", "c" ), started );
        assertSame( ENV, seen.get( 0 ) );
        assertTrue( sequenceLog.isEmpty(), sequenceLog.toString() );
    }

    @Test
    void continueStepFailureLogsOneWarnNamingTheStepAndLaterStepsStillRun() {
        new BootSequence( List.of(
            recording( "a", FailurePolicy.ABORT_REST ),
            throwing( "collaboration", FailurePolicy.CONTINUE, new IllegalStateException( "boom" ) ),
            recording( "c", FailurePolicy.ABORT_REST ) ) ).start( ENV );

        assertEquals( List.of( "a", "c" ), started );
        assertEquals( 1, sequenceLog.size(), sequenceLog.toString() );
        assertTrue( sequenceLog.get( 0 ).startsWith( "WARN " ), sequenceLog.toString() );
        assertTrue( sequenceLog.get( 0 ).contains( "collaboration" ) && sequenceLog.get( 0 ).contains( "boom" ),
            sequenceLog.toString() );
    }

    @Test
    void continueStepWithItsOwnFailureReportUsesItInsteadOfTheDefaultWarn() {
        final List< Exception > reported = new ArrayList<>();
        final IllegalStateException boom = new IllegalStateException( "boom" );
        new BootSequence( List.of(
            BootStep.continueOnFailure( "work", env -> { throw boom; }, reported::add ),
            recording( "after", FailurePolicy.ABORT_REST ) ) ).start( ENV );

        assertEquals( List.of( boom ), reported );
        assertEquals( List.of( "after" ), started );
        assertTrue( sequenceLog.isEmpty(), sequenceLog.toString() );
    }

    @Test
    void abortRestFailurePropagatesTheSameExceptionStopsLaterStepsAndLeavesTheLoggingToTheCaller() {
        final IllegalStateException boom = new IllegalStateException( "boom" );
        final BootSequence sequence = new BootSequence( List.of(
            recording( "a", FailurePolicy.ABORT_REST ),
            BootStep.abortRestOnFailure( "spine", env -> { throw boom; } ),
            recording( "c", FailurePolicy.CONTINUE ),
            recording( "d", FailurePolicy.ABORT_REST ) ) );

        assertSame( boom, assertThrows( IllegalStateException.class, () -> sequence.start( ENV ) ) );
        assertEquals( List.of( "a" ), started );
        assertTrue( sequenceLog.isEmpty(), "the caller's catch logs the abort once: " + sequenceLog );
    }

    @Test
    void checkedFailureOfAnAbortRestStepPropagatesUncheckedNamingTheStep() {
        final Exception checked = new Exception( "no jndi" );
        final BootSequence sequence = new BootSequence( List.of(
            throwing( "persistence", FailurePolicy.ABORT_REST, checked ),
            recording( "later", FailurePolicy.ABORT_REST ) ) );

        final IllegalStateException thrown = assertThrows( IllegalStateException.class, () -> sequence.start( ENV ) );
        assertSame( checked, thrown.getCause() );
        assertTrue( thrown.getMessage().contains( "persistence" ) && thrown.getMessage().contains( "no jndi" ),
            thrown.getMessage() );
        assertTrue( started.isEmpty() );
    }

    @Test
    void anErrorIsNeverAbsorbedEvenByAContinueStep() {
        final BootSequence sequence = new BootSequence( List.of(
            BootStep.continueOnFailure( "connectors", env -> { throw new LinkageError( "missing class" ); },
                e -> started.add( "reported" ) ),
            recording( "later", FailurePolicy.ABORT_REST ) ) );

        assertThrows( LinkageError.class, () -> sequence.start( ENV ) );
        assertTrue( started.isEmpty(), started.toString() );
    }

    @Test
    void stepsAreFixedAtConstruction() {
        final List< BootStep > declared = new ArrayList<>( List.of( recording( "a", FailurePolicy.ABORT_REST ) ) );
        final BootSequence sequence = new BootSequence( declared );
        declared.add( recording( "b", FailurePolicy.ABORT_REST ) );
        sequence.start( ENV );

        assertEquals( List.of( "a" ), started );
        assertEquals( List.of( "a" ), sequence.steps().stream().map( BootStep::name ).toList() );
    }
}

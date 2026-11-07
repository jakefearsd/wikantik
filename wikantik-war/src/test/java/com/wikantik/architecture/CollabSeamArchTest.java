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
package com.wikantik.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Seam rules for collaborative editing (docs/MultiEdit.MD sections 5.2 and 10): everything in
 * {@code com.wikantik.collab..} except {@code ..collab.adapter..} (production adapters, arriving in Phase 1) reaches
 * the engine, database and clock only through injected ports, so tests can drive every concurrent and failure case
 * with doubles.
 *
 * <p><b>Limitation:</b> ArchUnit sees method modifiers but not {@code synchronized} <em>blocks</em>, so those are not
 * covered and must be caught in review.</p>
 */
@AnalyzeClasses(
    packages = "com.wikantik.collab",
    importOptions = { ImportOption.DoNotIncludeTests.class }
)
class CollabSeamArchTest {

    private static final String ADAPTERS = "..collab.adapter..";

    /** Matches a call to {@code owner.name} taking exactly the given parameter types (the full signature). */
    private static DescribedPredicate<JavaCall<?>> callTo( final Class<?> owner, final String name,
                                                           final Class<?>... params ) {
        final java.util.List<String> paramNames = java.util.Arrays.stream( params ).map( Class::getName ).toList();
        return JavaCall.Predicates.target( HasName.Predicates.name( name ) )
            .and( JavaCall.Predicates.target(
                HasOwner.Predicates.With.owner( JavaClass.Predicates.equivalentTo( owner ) ) ) )
            .and( DescribedPredicate.describe( "with parameters " + paramNames,
                (JavaCall<?> c) -> c.getTarget().getRawParameterTypes().stream()
                    .map( JavaClass::getName ).toList().equals( paramNames ) ) );
    }

    /** Matches a call to any overload of {@code owner.name} (constructors are named {@code <init>}). */
    private static DescribedPredicate<JavaCall<?>> anyCallTo( final Class<?> owner, final String name ) {
        return JavaCall.Predicates.target( HasName.Predicates.name( name ) )
            .and( JavaCall.Predicates.target(
                HasOwner.Predicates.With.owner( JavaClass.Predicates.equivalentTo( owner ) ) ) );
    }

    /**
     * Zero-argument {@code now()} overloads and the zero-argument {@code Date} constructor only: {@code Instant.now(Clock)}
     * (and its siblings taking a {@code Clock}) is the sanctioned injected-clock pattern.
     */
    private static final DescribedPredicate<JavaCall<?>> READS_THE_CLOCK_OR_SLEEPS =
        callTo( Thread.class, "sleep", long.class )
            .or( callTo( Thread.class, "sleep", long.class, int.class ) )
            .or( callTo( Thread.class, "sleep", java.time.Duration.class ) )
            .or( callTo( System.class, "currentTimeMillis" ) )
            .or( callTo( System.class, "nanoTime" ) )
            .or( callTo( Instant.class, "now" ) )
            .or( callTo( LocalDateTime.class, "now" ) )
            .or( callTo( LocalDate.class, "now" ) )
            .or( callTo( ZonedDateTime.class, "now" ) )
            .or( callTo( OffsetDateTime.class, "now" ) )
            .or( callTo( Date.class, "<init>" ) )
            .or( callTo( LocalTime.class, "now" ) )
            .or( callTo( Year.class, "now" ) )
            .or( callTo( java.time.Clock.class, "systemUTC" ) )
            .or( callTo( java.time.Clock.class, "systemDefaultZone" ) )
            .or( callTo( java.time.Clock.class, "system", java.time.ZoneId.class ) )
            .or( JavaCall.Predicates.target( HasOwner.Predicates.With.owner( JavaClass.Predicates.equivalentTo( java.time.Clock.class ) ) )
                .and( DescribedPredicate.describe( "named tick*", (JavaCall<?> c) -> c.getName().startsWith( "tick" ) ) ) );

    @ArchTest
    static final ArchRule no_wall_clock_or_sleep =
        noClasses().that().resideInAPackage( "com.wikantik.collab.." )
            .and().resideOutsideOfPackage( ADAPTERS )
            .should().callCodeUnitWhere( READS_THE_CLOCK_OR_SLEEPS )
            .as( "collab core must not call Thread.sleep, System.currentTimeMillis/nanoTime, zero-arg"
               + " Instant.now/LocalDateTime.now/LocalDate.now/LocalTime.now/Year.now/ZonedDateTime.now/OffsetDateTime.now,"
               + " new Date(), or Clock.systemUTC/systemDefaultZone/Clock.system(zone)/Clock.tick* — inject a clock/scheduler" );

    /** Creating threads or executors; an injected executor or scheduler is the sanctioned pattern. */
    private static final DescribedPredicate<JavaCall<?>> CREATES_THREADS_OR_EXECUTORS =
        anyCallTo( Thread.class, "<init>" )
            .or( anyCallTo( Thread.class, "ofVirtual" ) )
            .or( anyCallTo( Thread.class, "ofPlatform" ) )
            .or( anyCallTo( Thread.class, "startVirtualThread" ) )
            .or( JavaCall.Predicates.target(
                HasOwner.Predicates.With.owner( JavaClass.Predicates.equivalentTo( Executors.class ) ) )
                .and( DescribedPredicate.describe( "not Executors.callable (wraps a task, creates no thread)",
                    (JavaCall<?> c) -> !"callable".equals( c.getName() ) ) ) )
            .or( anyCallTo( Timer.class, "<init>" ) )
            .or( callTo( CompletableFuture.class, "runAsync", Runnable.class ) )
            .or( callTo( CompletableFuture.class, "supplyAsync", java.util.function.Supplier.class ) )
            .or( anyCallTo( ForkJoinPool.class, "commonPool" ) )
            .or( anyCallTo( ForkJoinPool.class, "<init>" ) )
            .or( anyCallTo( ThreadPoolExecutor.class, "<init>" ) )
            .or( anyCallTo( ScheduledThreadPoolExecutor.class, "<init>" ) );

    @ArchTest
    static final ArchRule no_thread_or_executor_creation =
        noClasses().that().resideInAPackage( "com.wikantik.collab.." )
            .and().resideOutsideOfPackage( ADAPTERS )
            .should().callCodeUnitWhere( CREATES_THREADS_OR_EXECUTORS )
            .as( "collab core must not create threads or executors (new Thread, Thread.ofVirtual/ofPlatform/"
               + "startVirtualThread, any Executors factory but callable, ForkJoinPool, ThreadPoolExecutor,"
               + " ScheduledThreadPoolExecutor, java.util.Timer, CompletableFuture.runAsync/supplyAsync without an executor)"
               + " — inject an executor/scheduler" );

    @ArchTest
    static final ArchRule no_synchronized_methods =
        noMethods().that().areDeclaredInClassesThat().resideInAPackage( "com.wikantik.collab.." )
            .and().areDeclaredInClassesThat().resideOutsideOfPackage( ADAPTERS )
            .should().haveModifier( JavaModifier.SYNCHRONIZED )
            .as( "collab core must not declare synchronized methods" );

    @ArchTest
    static final ArchRule no_engine_or_sql_dependency =
        noClasses().that().resideInAPackage( "com.wikantik.collab.." )
            .and().resideOutsideOfPackage( ADAPTERS )
            .should().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.WikiEngine" )
            .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.api.core.Engine" )
            .orShould().dependOnClassesThat().resideInAnyPackage( "java.sql..", "javax.sql..", "com.wikantik.jdbc.." )
            .as( "collab core must not depend on WikiEngine, Engine, java.sql, javax.sql or com.wikantik.jdbc — use the ports" );

    /** Audit entries and metrics leave the core through the CollabAudit and CollabMetrics ports (section 9, 12.3). */
    @ArchTest
    static final ArchRule coreDoesNotDependOnMicrometerOrAudit =
        noClasses().that().resideInAPackage( "com.wikantik.collab.." )
            .and().resideOutsideOfPackage( ADAPTERS )
            .should().dependOnClassesThat().resideInAnyPackage( "io.micrometer..", "com.wikantik.audit..",
                "com.wikantik.api.observability.." )
            .as( "collab core must not depend on Micrometer, the audit service or the meter registry holder — use the"
               + " CollabAudit and CollabMetrics ports" );
}

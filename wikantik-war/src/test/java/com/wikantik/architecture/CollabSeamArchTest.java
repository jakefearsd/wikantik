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
import java.time.LocalDateTime;

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

    private static DescribedPredicate<JavaCall<?>> callTo( final Class<?> owner, final String name ) {
        return JavaCall.Predicates.target( HasName.Predicates.name( name ) )
            .and( JavaCall.Predicates.target(
                HasOwner.Predicates.With.owner( JavaClass.Predicates.equivalentTo( owner ) ) ) );
    }

    private static final DescribedPredicate<JavaCall<?>> READS_THE_CLOCK_OR_SLEEPS =
        callTo( Thread.class, "sleep" )
            .or( callTo( System.class, "currentTimeMillis" ) )
            .or( callTo( Instant.class, "now" ) )
            .or( callTo( LocalDateTime.class, "now" ) );

    @ArchTest
    static final ArchRule no_wall_clock_or_sleep =
        noClasses().that().resideInAPackage( "com.wikantik.collab.." )
            .and().resideOutsideOfPackage( ADAPTERS )
            .should().callMethodWhere( READS_THE_CLOCK_OR_SLEEPS )
            .as( "collab core must not call Thread.sleep, System.currentTimeMillis, Instant.now or "
               + "LocalDateTime.now — inject a clock/scheduler" );

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
            .orShould().dependOnClassesThat().resideInAPackage( "java.sql.." )
            .as( "collab core must not depend on WikiEngine, Engine or java.sql — use the ports" );
}

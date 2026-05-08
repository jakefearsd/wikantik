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

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.wikantik.WikiEngine;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * Architecture guards for the wikantik-main subsystem decomposition
 * (see docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md).
 *
 * <p>Each rule is wrapped in {@link com.tngtech.archunit.library.freeze.FreezingArchRule}
 * so existing violations are baselined into a violation store. New violations
 * fail the build; resolved violations require explicit removal from the store
 * (see {@code wikantik-main/src/test/resources/archunit_store/}). This means
 * the bar can only go up — the migration cannot accidentally reintroduce a
 * pattern we have already left behind.</p>
 *
 * <p>To address an existing violation: refactor the offender, run this test,
 * remove the now-stale entry from the store file, commit both. To add a new
 * legitimate caller (e.g. an approved bridge class during a phase migration):
 * justify in code review, add to the store explicitly with a comment.</p>
 *
 * <p><b>Module scope:</b> ArchUnit analyses the {@code wikantik-main}
 * compile classpath only. Other modules ({@code wikantik-rest},
 * {@code wikantik-tools}, {@code wikantik-admin-mcp},
 * {@code wikantik-knowledge}) carry their own {@code getManager()}
 * call sites and are out of scope for this test class. Cross-module
 * enforcement will arrive when those subsystems are extracted in
 * Phases 7–8 — at that point this test class is a candidate for
 * promotion to a dedicated {@code wikantik-archtest} module.</p>
 */
@AnalyzeClasses(
    packages = "com.wikantik",
    importOptions = { ImportOption.DoNotIncludeTests.class }
)
class DecompositionArchTest {

    /**
     * R-2: Service Locator pattern is frozen.
     *
     * <p>Calling {@link WikiEngine#getManager(Class)} is the legacy
     * service-locator pattern this decomposition is dismantling. New
     * callers are forbidden — services should receive their collaborators
     * via constructor parameters, ideally as part of a typed
     * {@code *Subsystem.Services} record.</p>
     *
     * <p>The current ~1,070 callers are baselined into the violation store.
     * Each phase of the decomposition burns down a subset; the store shrinks
     * monotonically until Phase 9 deletes both the registry and this rule
     * (replacing it with one that bans the API entirely).</p>
     */
    @ArchTest
    static final ArchRule no_new_get_manager_callers = freeze(
        noClasses()
            .should().callMethodWhere(
                com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    com.tngtech.archunit.core.domain.properties.HasName.Predicates.name( "getManager" )
                ).and(
                    com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo( WikiEngine.class )
                        )
                    )
                )
            )
            .as( "no new WikiEngine#getManager callers — services must receive "
               + "collaborators via constructor injection. See spec: "
               + "docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md" )
    );

    /**
     * R-3: TestEngine must not leak into production code.
     *
     * <p>{@code TestEngine} is a test-only convenience for spinning up a
     * minimally-wired engine in unit tests. Production code referencing it
     * indicates a lifecycle/wiring leak that breaks deployment. Frozen at
     * the current state (today: 0 violations) so the bar is fixed.</p>
     */
    @ArchTest
    static final ArchRule no_test_engine_in_production = freeze(
        noClasses()
            .should().dependOnClassesThat()
            .haveFullyQualifiedName( "com.wikantik.TestEngine" )
            .as( "production code may not depend on TestEngine — it's a "
               + "test fixture, not a runtime collaborator" )
    );

    /**
     * R-4: {@code WikiEngine#getManager} is banned outside approved wiring classes.
     *
     * <p>Phase 10 Ckpt A2 deleted the {@code managers} Map. The only legitimate
     * callers of {@code getManager} in production code are:
     * <ul>
     *   <li>{@code WikiEngine} itself (the dispatcher)</li>
     *   <li>{@code *SubsystemFactory} classes (subsystem boot)</li>
     *   <li>{@code *SubsystemBridge} classes (typed snapshot rebuilds)</li>
     *   <li>{@code *WiringHelper} classes (lazy post-boot wiring)</li>
     * </ul>
     * Existing violations in wikantik-main are frozen at their current count.
     * New callers outside the approved list fail the build.</p>
     */
    @ArchTest
    static final ArchRule no_get_manager_anywhere = freeze(
        noClasses()
            .that().resideInAPackage( "com.wikantik.." )
            .and().doNotHaveSimpleName( "WikiEngine" )
            .and().haveSimpleNameNotEndingWith( "SubsystemFactory" )
            .and().haveSimpleNameNotEndingWith( "SubsystemBridge" )
            .and().haveSimpleNameNotEndingWith( "WiringHelper" )
            .should().callMethodWhere(
                com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    com.tngtech.archunit.core.domain.properties.HasName.Predicates.name( "getManager" )
                ).and(
                    com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner(
                            com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo( WikiEngine.class )
                        )
                    )
                )
            )
            .as( "no_get_manager_anywhere — only WikiEngine, *SubsystemFactory, "
               + "*SubsystemBridge, and *WiringHelper may call WikiEngine#getManager; "
               + "all others must receive collaborators via constructor injection. "
               + "See Phase 10 Ckpt A2: docs/superpowers/plans/2026-05-08-decomposition-phase-10.md" )
    );
}

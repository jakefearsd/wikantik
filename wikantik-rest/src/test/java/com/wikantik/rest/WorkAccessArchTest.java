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
package com.wikantik.rest;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Every read of work data goes through {@code WorkService}, which is where project visibility is applied. This
 * keeps it that way: only the {@code com.wikantik.work} package (the store and the service) and the one wiring class
 * {@code com.wikantik.work.runtime.WorkWiring} that builds the store may touch {@code WorkStore} or {@code JdbcWorkStore}. Not frozen: there are no
 * violations to baseline and none may be added.
 */
@AnalyzeClasses( packages = "com.wikantik", importOptions = ImportOption.DoNotIncludeTests.class )
class WorkAccessArchTest {

    /**
     * Sweeping lapsed claims is a system task for the internal scheduler. No REST class may call it: a route that
     * did would let any caller trigger expiry on demand.
     */
    @ArchTest
    static final ArchRule no_rest_route_sweeps_expired_claims = noClasses()
        .that( resideInAPackage( "com.wikantik.rest.." ) )
        .should().callMethod( com.wikantik.work.WorkClaimsService.class, "sweepExpiredClaims" )
        .because( "claim expiry is run by the scheduler, never on a caller's request" );

    @ArchTest
    static final ArchRule only_the_work_packages_touch_the_store = noClasses()
        .that( not( resideInAPackage( "com.wikantik.work" ) ) )
        .and( not( equivalentTo( com.wikantik.work.runtime.WorkWiring.class ) ) )
        .should().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.JdbcWorkStore" )
        .because( "WorkService is the only door to work data; the store has no visibility check" );
}

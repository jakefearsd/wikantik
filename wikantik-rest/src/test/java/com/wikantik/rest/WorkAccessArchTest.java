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
 * {@code com.wikantik.work.runtime.WorkWiring} that builds the store may touch {@code WorkStore}, {@code JdbcWorkStore}
 * or the sub-store interfaces ({@code WorkCatalogStore}, {@code WorkRegistryStore}, {@code WorkClaimStore}, {@code WorkCredentialStore}). Not frozen: there are no
 * violations to baseline and none may be added.
 *
 * <p>Classes come from {@link com.wikantik.architecture.ProductionPackageLocations}, not {@code packages =}: the
 * latter walks the whole {@code target/test-classes} root, where other forks' {@code TestEngine} page repositories
 * come and go, and failed on one that vanished mid-scan.</p>
 */
@AnalyzeClasses( locations = com.wikantik.architecture.ProductionPackageLocations.class,
    importOptions = ImportOption.DoNotIncludeTests.class )
class WorkAccessArchTest {

    /**
     * Sweeping lapsed claims is a system task for the internal scheduler. No REST class may call it: a route that
     * did would let any caller trigger expiry on demand.
     */
    @ArchTest
    static final ArchRule no_rest_route_sweeps_expired_claims = noClasses()
        .that( resideInAPackage( "com.wikantik.rest.." ) )
        .should().callMethod( com.wikantik.work.WorkClaimsService.class, "sweepExpiredClaims" )
        .orShould().callMethod( com.wikantik.work.WorkClaimsService.class, "sweepStanding" )
        .because( "the claim sweeps (expiry, and sponsors who lost standing) are run by the scheduler, never on a "
            + "caller's request" );

    @ArchTest
    static final ArchRule only_the_work_packages_touch_the_store = noClasses()
        .that( not( resideInAPackage( "com.wikantik.work" ) ) )
        .and( not( equivalentTo( com.wikantik.work.runtime.WorkWiring.class ) ) )
        .should().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.JdbcWorkStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkCatalogStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkRegistryStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkClaimStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkCredentialStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkFindStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkFilterStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkAttachmentStore" )
        .orShould().dependOnClassesThat().haveFullyQualifiedName( "com.wikantik.work.WorkCodeHostStore" )
        .because( "WorkService is the only door to work data; the store and its sub-stores have no visibility check" );

    /**
     * The flake's cause, made deterministic: a directory in the {@code target/test-classes} root that cannot be read
     * stands in for a {@code TestEngine} page repository deleted by another fork mid-scan. Importing must not walk the
     * classpath root, so it must not trip over it.
     */
    @org.junit.jupiter.api.Test
    void importingNeverWalksTheTestClassesRoot() throws Exception {
        final java.nio.file.Path root = java.nio.file.Path.of(
            WorkAccessArchTest.class.getProtectionDomain().getCodeSource().getLocation().toURI() );
        final java.nio.file.Path probe = root.resolve( "archprobe-" + java.util.UUID.randomUUID() );
        final java.nio.file.Path repo = java.nio.file.Files.createDirectories( probe.resolve( "testrepository" ) );
        java.nio.file.Files.writeString( repo.resolve( "Main.md" ), "x" );
        java.nio.file.Files.setPosixFilePermissions( repo, java.util.Set.of() );
        try {
            final com.tngtech.archunit.core.domain.JavaClasses imported = new com.tngtech.archunit.core.importer
                .ClassFileImporter().withImportOption( new ImportOption.DoNotIncludeTests() )
                .importLocations( new com.wikantik.architecture.ProductionPackageLocations().get( getClass() ) );
            org.junit.jupiter.api.Assertions.assertTrue( imported.contain( WorkResourceBase.class )
                && imported.contain( com.wikantik.work.runtime.WorkWiring.class )
                && imported.contain( com.wikantik.work.WorkStore.class )
                && imported.contain( com.wikantik.work.WorkClaimsService.class ),
                "the rules above see the rest, main and work classes they are about" );
        } finally {
            java.nio.file.Files.setPosixFilePermissions( repo, java.nio.file.attribute.PosixFilePermissions.fromString(
                "rwx------" ) );
            java.nio.file.Files.delete( repo.resolve( "Main.md" ) );
            java.nio.file.Files.delete( repo );
            java.nio.file.Files.delete( probe );
        }
    }
}

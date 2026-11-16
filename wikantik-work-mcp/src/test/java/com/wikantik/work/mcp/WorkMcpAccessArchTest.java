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
package com.wikantik.work.mcp;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.wikantik.work.WorkCatalogStore;
import com.wikantik.work.WorkClaimStore;
import com.wikantik.work.WorkClaimsService;
import com.wikantik.work.WorkRegistryStore;
import com.wikantik.work.WorkStore;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The MCP layer reaches work data through {@code WorkService} only, and never starts the claim sweep. */
class WorkMcpAccessArchTest {

    private static final String PKG = "com.wikantik.work.mcp..";

    private static final ArchRule NO_STORE = noClasses().that().resideInAPackage( PKG )
        .should().dependOnClassesThat().areAssignableTo( WorkStore.class )
        .orShould().dependOnClassesThat().areAssignableTo( WorkClaimStore.class )
        .orShould().dependOnClassesThat().areAssignableTo( com.wikantik.work.WorkCredentialStore.class )
        .orShould().dependOnClassesThat().areAssignableTo( WorkRegistryStore.class )
        .orShould().dependOnClassesThat().areAssignableTo( WorkCatalogStore.class )
        .orShould().dependOnClassesThat().resideInAPackage( "com.wikantik.jdbc.." )
        .orShould().dependOnClassesThat().resideInAPackage( "javax.sql.." );

    private static final ArchRule NO_JDBC_STORE_CLASS = noClasses().that().resideInAPackage( PKG )
        .should().dependOnClassesThat().haveSimpleNameStartingWith( "Jdbc" );

    private static final ArchRule NO_SWEEP = noClasses().that().resideInAPackage( PKG )
        .should().callMethod( WorkClaimsService.class, "sweepExpiredClaims" )
        .orShould().callMethod( WorkClaimsService.class, "sweepStanding" );

    private static JavaClasses production() {
        return new ClassFileImporter().withImportOption( ImportOption.Predefined.DO_NOT_INCLUDE_TESTS )
            .importPackages( "com.wikantik.work.mcp" );
    }

    /** A stand-in in the checked package, to prove each rule can fail. */
    static final class Offender {
        @SuppressWarnings( "unused" )
        void reach( final WorkStore store, final WorkClaimsService claims ) throws java.sql.SQLException {
            store.products();
            claims.sweepExpiredClaims();
            new com.wikantik.work.JdbcWorkStore( null );
        }
    }

    /** A stand-in that reaches the claim store by name only. */
    static final class ClaimStoreOffender {
        @SuppressWarnings( "unused" )
        void reach( final WorkClaimStore claims ) {
            claims.hashCode();
        }
    }

    @Test
    void noWorkMcpClassTouchesTheStore() {
        NO_STORE.check( production() );
        NO_JDBC_STORE_CLASS.check( production() );
    }

    @Test
    void noWorkMcpClassStartsTheSweep() {
        NO_SWEEP.check( production() );
    }

    @Test
    void theRulesCanFail() {
        final JavaClasses withOffender = new ClassFileImporter().importClasses( Offender.class );
        assertThrows( AssertionError.class, () -> NO_STORE.check( withOffender ) );
        assertThrows( AssertionError.class, () -> NO_JDBC_STORE_CLASS.check( withOffender ) );
        assertThrows( AssertionError.class, () -> NO_SWEEP.check( withOffender ) );
        final JavaClasses claimOnly = new ClassFileImporter().importClasses( ClaimStoreOffender.class );
        assertThrows( AssertionError.class, () -> NO_STORE.check( claimOnly ), "a sub-store is the store too" );
    }
}

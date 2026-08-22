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
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.domain.properties.HasOwner;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import javax.sql.DataSource;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.freeze.FreezingArchRule.freeze;

/**
 * Architecture guard for "one way to touch the database" — see
 * docs/superpowers/plans/2026-08-22-one-way-to-touch-the-database.md.
 *
 * <p>Rule J-1 forbids opening a JDBC connection, or driving one directly
 * (preparing/executing statements, toggling autocommit, committing, or
 * rolling back) anywhere outside {@code com.wikantik.jdbc..} — the module
 * that owns {@link com.wikantik.jdbc.Jdbc}, the single sanctioned data-access
 * primitive. Production code should hold a {@code Jdbc} (or extend
 * {@code JdbcSupport}) and call its {@code query}/{@code queryOne}/
 * {@code update}/{@code batch}/{@code forEachRow}/{@code execute}/
 * {@code inTransaction} methods instead.</p>
 *
 * <p>The rule is wrapped in {@link FreezingArchRule} so the ~55 existing
 * production call sites are baselined into a violation store (see
 * {@code wikantik-war/src/test/resources/archunit_store/}); new violations
 * fail the build immediately, and the wave-by-wave migration burns the store
 * down to zero (Phase 3 of the plan then converts the rule to a plain,
 * unfrozen {@code ArchRule} once the store is empty).</p>
 *
 * <p>To address an existing violation: migrate the offender onto
 * {@code Jdbc}/{@code JdbcSupport}, run this test, remove the now-stale
 * entry from the store file, commit both.</p>
 *
 * <p><b>Why {@code wikantik-war}, not {@code wikantik-main}:</b> this is the
 * one module whose test classpath reaches every other module that touches
 * JDBC (connectors, insights, knowledge, rest, admin-mcp, extract-cli), so
 * it is the only place a single {@code @AnalyzeClasses(packages =
 * "com.wikantik")} sees the whole surface.</p>
 */
@AnalyzeClasses(
    packages = "com.wikantik",
    importOptions = { ImportOption.DoNotIncludeTests.class }
)
class JdbcAccessArchTest {

    /**
     * Permanent allowlist: page-authored SQL plugin, disabled by default
     * behind {@code wikantik.plugin.jdbc.enabled}, deliberately manages its
     * own {@link java.sql.DriverManager} connection lifecycle rather than
     * going through the wiki's own {@code DataSource} (it lets a wiki page
     * point at an arbitrary JDBC URL). Out of scope for this migration.
     *
     * <p>{@code doNotBelongToAnyOf} (rather than {@code doNotHaveFullyQualifiedName})
     * is deliberate: the actual violator is the nested helper
     * {@code JDBCPlugin$ConnectionConfig}, and "belongs to" covers nested
     * classes of the allowlisted top-level class too.</p>
     */
    private static final Class<?> JDBC_PLUGIN = com.wikantik.plugin.JDBCPlugin.class;

    private static final DescribedPredicate<JavaCall<?>> OPENS_OR_DRIVES_A_CONNECTION =
        callTo( DataSource.class, "getConnection" )
            .or( callTo( java.sql.DriverManager.class, "getConnection" ) )
            .or( callTo( java.sql.Connection.class,
                "prepareStatement|prepareCall|createStatement|setAutoCommit|commit|rollback" ) );

    /**
     * J-1: only {@code com.wikantik.jdbc..} (and the allowlisted
     * {@code JDBCPlugin}) may open or directly drive a JDBC connection.
     */
    @ArchTest
    static final ArchRule only_jdbc_module_touches_connections = freeze(
        noClasses()
            .that().resideOutsideOfPackage( "com.wikantik.jdbc.." )
            .and().doNotBelongToAnyOf( JDBC_PLUGIN )
            .should().callMethodWhere( OPENS_OR_DRIVES_A_CONNECTION )
            // The .as() text below is the FreezingArchRule violation-store KEY and
            // MUST match archunit_store/stored.rules byte-for-byte — do not edit it.
            .as( "J-1: no class outside com.wikantik.jdbc.. (except the allowlisted "
               + "com.wikantik.plugin.JDBCPlugin) may call DataSource#getConnection, "
               + "DriverManager#getConnection, or Connection#{prepareStatement,prepareCall,"
               + "createStatement,setAutoCommit,commit,rollback} — use com.wikantik.jdbc.Jdbc. "
               + "See docs/superpowers/plans/2026-08-22-one-way-to-touch-the-database.md" )
    );

    /**
     * Builds a predicate matching calls to a method (or, via {@code |}-separated
     * alternation in {@code nameRegex}, any of several overloaded methods) whose
     * declared owner is assignable to {@code owner}. Matching on the call
     * target's declared owner type (rather than the exact receiver class) is
     * the robust form here: a caller may hold the target typed as a pooled
     * subtype (e.g. a connection-pool's {@code Connection} wrapper), and
     * {@code assignableTo} still catches it. Two separate {@code target(...)}
     * calls, combined with {@code .and()}, is the pattern DecompositionArchTest
     * already uses — it lets Java's target-typed generic inference resolve the
     * owner type argument without an invalid explicit type witness on a
     * non-generic instance method.
     */
    private static DescribedPredicate<JavaCall<?>> callTo( Class<?> owner, String nameRegex )
    {
        return JavaCall.Predicates.target( HasName.Predicates.nameMatching( nameRegex ) )
            .and( JavaCall.Predicates.target(
                HasOwner.Predicates.With.owner( JavaClass.Predicates.assignableTo( owner ) ) ) );
    }
}

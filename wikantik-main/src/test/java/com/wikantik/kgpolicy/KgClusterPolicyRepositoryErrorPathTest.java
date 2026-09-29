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
package com.wikantik.kgpolicy;

import com.wikantik.api.kgpolicy.ClusterAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every public method on {@link KgClusterPolicyRepository} wraps its {@link SQLException}
 * in a {@link RuntimeException} with a context message after a {@code LOG.warn}. A
 * {@link DataSource} that always fails to hand out a connection drives each method into
 * that catch block without needing a live database or a contrived constraint violation.
 */
class KgClusterPolicyRepositoryErrorPathTest {

    private KgClusterPolicyRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        final DataSource broken = mock( DataSource.class );
        when( broken.getConnection() ).thenThrow( new SQLException( "simulated connection failure" ) );
        repo = new KgClusterPolicyRepository( broken );
    }

    @Test
    void findWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, () -> repo.find( "java" ) );
        assertTrue( ex.getMessage().contains( "find policy for java" ), ex.getMessage() );
    }

    @Test
    void listWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, repo::list );
        assertTrue( ex.getMessage().contains( "list cluster policies" ), ex.getMessage() );
    }

    @Test
    void upsertWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> repo.upsert( "java", ClusterAction.INCLUDE, "reason", "admin" ) );
        assertTrue( ex.getMessage().contains( "upsert policy for java" ), ex.getMessage() );
    }

    @Test
    void deleteWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, () -> repo.delete( "java" ) );
        assertTrue( ex.getMessage().contains( "delete policy for java" ), ex.getMessage() );
    }

    @Test
    void markReviewedWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class, () -> repo.markReviewed( "java" ) );
        assertTrue( ex.getMessage().contains( "markReviewed for java" ), ex.getMessage() );
    }

    @Test
    void appendAuditWrapsFailure() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> repo.appendAudit( "java", "include", "exclude", "reason", "admin" ) );
        assertTrue( ex.getMessage().contains( "appendAudit for java" ), ex.getMessage() );
    }

    @Test
    void listAuditWrapsFailureWithClusterFilter() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> repo.listAudit( Optional.of( "java" ), 10 ) );
        assertTrue( ex.getMessage().contains( "listAudit for java" ), ex.getMessage() );
    }

    @Test
    void listAuditWrapsFailureWithoutClusterFilter() {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> repo.listAudit( Optional.empty(), 10 ) );
        assertTrue( ex.getMessage().contains( "listAudit" ), ex.getMessage() );
    }
}

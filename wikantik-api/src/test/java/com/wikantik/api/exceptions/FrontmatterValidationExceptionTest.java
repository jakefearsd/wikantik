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
package com.wikantik.api.exceptions;

import com.wikantik.api.frontmatter.schema.FieldViolation;
import com.wikantik.api.frontmatter.schema.Severity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontmatterValidationExceptionTest {

    private static final FieldViolation ONE = FieldViolation.of(
            "cluster", Severity.ERROR, "cluster.slug.malformed", "cluster must be kebab-case" );
    private static final FieldViolation TWO = FieldViolation.of(
            "status", Severity.ERROR, "status.noncanonical", "status is not a known value" );

    @Test
    void buildsAJoinedMessageAndExposesTheViolationsUnmodified() {
        final FrontmatterValidationException ex = new FrontmatterValidationException( List.of( ONE, TWO ) );

        assertTrue( ex.getMessage().contains( "cluster must be kebab-case" ), ex.getMessage() );
        assertTrue( ex.getMessage().contains( "status is not a known value" ), ex.getMessage() );
        assertEquals( List.of( ONE, TWO ), ex.violations() );
    }

    @Test
    void carriesACauseAlongsideTheViolations() {
        final Throwable cause = new IllegalStateException( "schema load failed" );
        final FrontmatterValidationException ex =
                new FrontmatterValidationException( List.of( ONE ), cause );

        assertEquals( List.of( ONE ), ex.violations() );
        assertSame( cause, ex.getCause() );
    }
}

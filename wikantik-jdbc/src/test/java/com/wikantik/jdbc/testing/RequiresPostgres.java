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
package com.wikantik.jdbc.testing;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class as needing {@link PostgresTestDb} (a Docker-backed Postgres container). The
 * replacement for {@code @Testcontainers(disabledWithoutDocker = true)} on container-managed
 * tests: when Docker is unavailable, the class is disabled with a visible reason locally, or —
 * with {@code -Dtests.requireDocker=true} — execution fails outright rather than
 * silently skipping.
 *
 * @see RequiresPostgresCondition
 */
@Retention( RetentionPolicy.RUNTIME )
@Target( { ElementType.TYPE, ElementType.METHOD } )
@ExtendWith( RequiresPostgresCondition.class )
public @interface RequiresPostgres {
}

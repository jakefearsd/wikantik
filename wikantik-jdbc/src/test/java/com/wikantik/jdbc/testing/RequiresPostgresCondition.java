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

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Backs {@link RequiresPostgres}: evaluates Docker availability once per JVM (the probe itself,
 * {@link PostgresTestDb#checkDockerAvailability()}, is cheap and side-effect-free — it does not
 * start the container). Docker present -&gt; enabled. Docker absent -&gt;
 * {@link org.junit.jupiter.api.extension.ConditionEvaluationResult#disabled disabled} with a
 * visible reason, UNLESS {@code -Dtests.requireDocker=true}, in which case this throws
 * so the run fails rather than silently skips.
 */
final class RequiresPostgresCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition( final ExtensionContext context ) {
        final PostgresTestDb.DockerAvailability availability = PostgresTestDb.checkDockerAvailability();
        if ( availability.available() ) {
            return ConditionEvaluationResult.enabled( "Docker is available" );
        }
        if ( Boolean.getBoolean( "tests.requireDocker" ) ) {
            // Throwing (rather than returning "disabled") makes this a hard failure of the
            // test/container, per the CI contract: an absent Docker daemon must fail the run,
            // not silently skip 70+ Postgres-backed tests.
            throw new IllegalStateException(
                "Docker not available: " + availability.reason()
                    + " (-Dtests.requireDocker=true requires Docker to be present)" );
        }
        return ConditionEvaluationResult.disabled( "Docker not available: " + availability.reason() );
    }
}

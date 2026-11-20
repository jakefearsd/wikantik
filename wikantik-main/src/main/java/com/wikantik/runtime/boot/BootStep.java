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
package com.wikantik.runtime.boot;

import java.util.function.Consumer;

/**
 * One declared stage of a {@link BootSequence}: a named piece of start-up wiring plus the {@link FailurePolicy}
 * that says what its failure does to the stages declared after it.
 *
 * <p>A step reads its collaborators from the {@link WiringEnv} it is started with. Steps are typically declared as
 * lambdas through {@link #abortRestOnFailure} and {@link #continueOnFailure}; implement the interface directly only
 * when a step needs more than that.</p>
 */
public interface BootStep {

    /** The work a declared step does when started. */
    @FunctionalInterface
    interface Action {
        /**
         * Runs the step's wiring.
         *
         * @param env the collaborators resolved before the sequence started
         * @throws Exception any failure; the step's {@link FailurePolicy} decides what it does to the sequence
         */
        void start( WiringEnv env ) throws Exception;
    }

    /** @return a short, stable name for the step, used in failure reports */
    String name();

    /** @return what a failure of this step does to the steps after it */
    FailurePolicy policy();

    /**
     * Runs the step's wiring.
     *
     * @param env the collaborators resolved before the sequence started
     * @throws Exception any failure; {@link #policy()} decides whether the sequence continues
     */
    void start( WiringEnv env ) throws Exception;

    /**
     * Reports a start failure that a {@link FailurePolicy#CONTINUE} policy absorbed, and resets any state the step
     * half-built. Never called for {@link FailurePolicy#ABORT_REST}. The default logs one WARN naming the step.
     *
     * @param failure what {@link #start} threw
     */
    default void reportContinuedFailure( final Exception failure ) {
        BootSequence.LOG.warn( "Boot step '{}' failed (continuing without it): {}", name(), failure.getMessage(), failure );
    }

    /**
     * Declares a step whose failure abandons the rest of the sequence.
     *
     * @param name   the step's name
     * @param action the step's wiring
     * @return the step
     */
    static BootStep abortRestOnFailure( final String name, final Action action ) {
        return new DeclaredBootStep( name, FailurePolicy.ABORT_REST, action, null );
    }

    /**
     * Declares a step whose failure is reported by {@code report} (in place of the default WARN) before the sequence
     * goes on with the next step.
     *
     * @param name   the step's name
     * @param action the step's wiring
     * @param report logs the failure in the subsystem's own words and resets what it half-built
     * @return the step
     */
    static BootStep continueOnFailure( final String name, final Action action, final Consumer< Exception > report ) {
        return new DeclaredBootStep( name, FailurePolicy.CONTINUE, action, report );
    }
}

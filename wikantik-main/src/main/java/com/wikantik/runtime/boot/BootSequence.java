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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * An ordered list of {@link BootStep}s started one after another, each failure handled by its step's declared
 * {@link FailurePolicy} instead of by where a try/catch happens to sit.
 *
 * <p>The declared order is the start order and is load-bearing: a step may use what an earlier step built.
 * A {@link FailurePolicy#CONTINUE} step's failure is reported through {@link BootStep#reportContinuedFailure} and the
 * next step starts. A {@link FailurePolicy#ABORT_REST} step's failure ends {@link #start}: no later step runs and
 * the exception propagates to the caller, which logs it once. An {@link Error} is never absorbed, whatever the
 * policy.</p>
 */
public final class BootSequence {

    static final Logger LOG = LogManager.getLogger( BootSequence.class );

    private final List< BootStep > steps;

    /**
     * @param steps the steps in start order; copied, so later changes to the list do not affect the sequence
     */
    public BootSequence( final List< BootStep > steps ) {
        this.steps = List.copyOf( steps );
    }

    /** @return the steps in start order (unmodifiable) */
    public List< BootStep > steps() {
        return steps;
    }

    /**
     * Starts every step in order, applying each step's failure policy.
     *
     * @param env the collaborators every step reads
     * @throws RuntimeException the failure of an {@link FailurePolicy#ABORT_REST} step: the step's own runtime
     *         exception unchanged, or an {@link IllegalStateException} naming the step and wrapping a checked one
     */
    public void start( final WiringEnv env ) {
        for ( final BootStep step : steps ) {
            try {
                step.start( env );
            } catch ( final RuntimeException e ) {
                absorbOrRethrow( step, e, e );
            } catch ( final Exception e ) {
                absorbOrRethrow( step, e,
                    new IllegalStateException( "Boot step '" + step.name() + "' failed: " + e.getMessage(), e ) );
            }
        }
    }

    private static void absorbOrRethrow( final BootStep step, final Exception failure, final RuntimeException abort ) {
        if ( step.policy() == FailurePolicy.CONTINUE ) {
            step.reportContinuedFailure( failure );
        } else {
            throw abort;
        }
    }
}

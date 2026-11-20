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

import java.util.Objects;
import java.util.function.Consumer;

/** The {@link BootStep} behind {@link BootStep#abortRestOnFailure} and {@link BootStep#continueOnFailure}. */
final class DeclaredBootStep implements BootStep {

    private final String name;
    private final FailurePolicy policy;
    private final Action action;
    private final Consumer< Exception > report;

    DeclaredBootStep( final String name, final FailurePolicy policy, final Action action,
                      final Consumer< Exception > report ) {
        this.name = Objects.requireNonNull( name, "name" );
        this.policy = Objects.requireNonNull( policy, "policy" );
        this.action = Objects.requireNonNull( action, "action" );
        this.report = report;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public FailurePolicy policy() {
        return policy;
    }

    @Override
    public void start( final WiringEnv env ) throws Exception {
        action.start( env );
    }

    @Override
    public void reportContinuedFailure( final Exception failure ) {
        if ( report == null ) {
            BootStep.super.reportContinuedFailure( failure );
        } else {
            report.accept( failure );
        }
    }

    @Override
    public String toString() {
        return name + " (" + policy + ")";
    }
}

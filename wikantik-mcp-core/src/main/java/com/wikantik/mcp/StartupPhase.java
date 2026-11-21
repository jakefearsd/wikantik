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
package com.wikantik.mcp;

import org.apache.logging.log4j.Logger;

/**
 * One guarded step of an MCP endpoint's startup. A failure is logged at ERROR with the endpoint's own
 * message (one {@code {}} slot for the cause, then the exception for the stack trace) and reported to
 * the caller, which stops startup — the shared shape of the admin and knowledge initializers' phases.
 */
public final class StartupPhase {

    /** A startup step that yields a value and may fail. */
    @FunctionalInterface
    public interface Step< T > {
        T run() throws Exception;
    }

    /** A startup step with no result that may fail. */
    @FunctionalInterface
    public interface VoidStep {
        void run() throws Exception;
    }

    private StartupPhase() {
    }

    /**
     * Runs {@code step}; returns its value, or null after logging {@code failureMessage} if it threw. Null is
     * the failure sentinel, so a step must not legitimately return null.
     */
    public static < T > T call( final Logger log, final String failureMessage, final Step< T > step ) {
        try {
            return step.run();
        } catch ( final Exception e ) {
            log.error( failureMessage, e.getMessage(), e );
            return null;
        }
    }

    /** Runs {@code step}; returns true, or false after logging {@code failureMessage} if it threw. */
    public static boolean run( final Logger log, final String failureMessage, final VoidStep step ) {
        try {
            step.run();
            return true;
        } catch ( final Exception e ) {
            log.error( failureMessage, e.getMessage(), e );
            return false;
        }
    }
}

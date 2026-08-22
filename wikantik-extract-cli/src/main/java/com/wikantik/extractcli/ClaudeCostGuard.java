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
package com.wikantik.extractcli;

/**
 * The opt-in gate every CLI path that spends money on the Anthropic API must pass.
 *
 * <p>Three CLI entry points reach a {@code ClaudeProposalJudge} or Claude extractor:
 * {@code BootstrapExtractionCli}'s {@code --judge claude} and its extractor, and
 * {@code JudgeExperimentCli}'s {@code --judge claude}. Each is gated by a distinct
 * {@code -D} property so enabling one does not silently enable the others.
 *
 * <p>The checks live here rather than in whichever CLI happened to write them first.
 * {@code JudgeExperimentCli} previously carried a hand-copied second implementation; the two
 * agreed at the time, but a guard on real spend is the last thing that should depend on two
 * copies staying in step — a weakening applied to one and not the other bills the user.
 */
final class ClaudeCostGuard {

    private ClaudeCostGuard() {
    }

    /**
     * Resolves the Anthropic API key for a paid code path, refusing unless the caller has
     * explicitly opted in.
     *
     * <p>Three things must hold, in this order: the gate property is {@code true}, the caller
     * named an environment variable to read the key from, and that variable actually holds
     * one. The key is only ever read from the environment — never a flag or a property — so
     * it cannot end up in shell history or a process listing.
     *
     * @param keyEnv       name of the environment variable holding the API key.
     * @param gateProp     the {@code -D} property that must be {@code true} to proceed.
     * @param contextLabel what the caller was trying to do, quoted into the refusal, e.g.
     *                     {@code "--judge claude"}.
     * @return the API key, never blank.
     * @throws IllegalStateException if the gate is shut, no env var was named, or it is empty.
     */
    static String resolveKey( final String keyEnv, final String gateProp, final String contextLabel ) {
        if( !Boolean.parseBoolean( System.getProperty( gateProp, "false" ) ) ) {
            throw new IllegalStateException(
                contextLabel + " requires -D" + gateProp + "=true (gated cost guard)." );
        }
        if( keyEnv == null || keyEnv.isBlank() ) {
            throw new IllegalStateException(
                contextLabel + " requires --anthropic-key-env <VAR> naming the env var "
              + "that holds the Anthropic API key." );
        }
        final String key = System.getenv( keyEnv );
        if( key == null || key.isBlank() ) {
            throw new IllegalStateException(
                "environment variable '" + keyEnv + "' is unset or empty." );
        }
        return key;
    }
}

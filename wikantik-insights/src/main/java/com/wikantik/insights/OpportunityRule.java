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
package com.wikantik.insights;

import java.util.List;

/**
 * One native opportunity rule (content-intelligence design §7.3), run by
 * {@link OpportunityEngine#evaluateGated} over a shared {@link EvaluationInput}.
 *
 * <p>Contract: an implementation is a pure function of its input -- no I/O, no clock reads -- and
 * returns a new list. A rule whose {@link #gated()} is {@code true} is not called at all while the
 * §7.3.0 site traffic gate is closed; the engine reports it in {@link Backlog#suppressed()}
 * instead.</p>
 */
interface OpportunityRule {

    /** The type identifier carried by every {@link Opportunity} this rule produces. */
    String type();

    /** Whether the §7.3.0 site traffic gate applies to this rule. */
    boolean gated();

    /**
     * Runs the rule.
     *
     * @param in everything the evaluation reads
     * @return this rule's opportunities, in no particular order
     */
    List<Opportunity> evaluate( EvaluationInput in );
}

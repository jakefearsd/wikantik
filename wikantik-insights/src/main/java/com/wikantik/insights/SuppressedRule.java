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

/**
 * One rule that did not run for this evaluation, and why (content-intelligence design §7.3.0).
 * Today the only reason is the site-level traffic gate: below
 * {@link OpportunityEngineConfig#gateImpressions28d()} total site impressions in 28 days, the
 * three visibility-driven rules ({@code ENGINE_DIVERGENCE}, {@code VOCABULARY_GAP},
 * {@code STALE_HIGH_TRAFFIC}) are suppressed rather than silently returning nothing, so a read
 * surface can report the measured value and the value required instead of leaving the absence
 * unexplained.
 *
 * @param type     the suppressed rule's type identifier (matches {@link Opportunity#type()})
 * @param reason   why the rule did not run, e.g. {@code "traffic_gate"}
 * @param measured the observed value that failed to clear the requirement
 * @param required the value that would have been required for the rule to run
 */
public record SuppressedRule( String type, String reason, double measured, double required ) {
}

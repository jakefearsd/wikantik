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
 * One evaluated {@code content_change_log} row usable for self-calibration (design §7.4.4) --
 * what a rule predicted versus what its change actually delivered.
 *
 * <p>Read via {@link InsightsStore#calibrationSamples}, which only returns rows where
 * {@code evaluated_at}, {@code opportunity_type}, {@code predicted_priority} and
 * {@code effect_click_delta} are all non-null -- every field this record exposes is guaranteed
 * present.</p>
 *
 * @param opportunityType    the rule that motivated the change (matches {@link Opportunity#type()})
 * @param predictedPriority  the priority the engine assigned when the change was proposed
 * @param realizedClickDelta the realised, site-adjusted click delta the evaluator measured
 */
public record CalibrationSample( String opportunityType, double predictedPriority,
                                 double realizedClickDelta ) {
}

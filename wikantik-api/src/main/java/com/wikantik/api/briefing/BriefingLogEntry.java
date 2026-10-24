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
package com.wikantik.api.briefing;

/**
 * One logged briefing request, for telemetry grounding future eval work.
 *
 * <p>{@code pinCount} counts included {@code pin} items; {@code pointerCount} counts budget-trimmed
 * ({@code included == false}) {@code pin} and {@code cluster} items only; {@code citedCount} and
 * {@code elsewhereCount} count the {@code cited} and {@code elsewhere} items whether or not they were
 * trimmed. {@code scopeMode} is the effective scope mode in wire form ({@code "prefer"} or
 * {@code "strict"}).</p>
 */
public record BriefingLogEntry( String pins, String clusters, boolean promptPresent,
                                int budgetRequested, int budgetUsed, int sectionCount,
                                int pinCount, int pointerCount, String surface,
                                int citedCount, int elsewhereCount, String scopeMode ) {}

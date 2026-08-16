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
 * Per-engine totals for a single {@code snapshot_date}, aggregated from page-rollup rows
 * ({@code query_text = ''}) only — see {@link VisibilityRow} for why the rollup row is
 * authoritative for page totals.
 *
 * <p>{@code position} is {@code null} when the engine emits no positive {@code position}
 * value for this snapshot (Yandex never emits {@code position} at all). Callers must
 * serialize a {@code null} position as JSON {@code null}, never {@code 0} — {@code 0} would
 * falsely claim rank 1.</p>
 *
 * <p>An engine that emits no page-rollup rows at all for a given date (again, Yandex) simply
 * has no {@code EngineTotal} in the result — it is never synthesized as a zero row.</p>
 */
public record EngineTotal( String engine, long clicks, long impressions, Double position ) {
}

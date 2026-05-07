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
package com.wikantik.render.subsystem.spam;

/**
 * Akismet integration + bot-trap hidden-field check bucket of the
 * decomposed SpamFilter.
 *
 * <p>Phase 6 Checkpoint 1 of the wikantik-main subsystem decomposition
 * declares this as an empty marker interface so that
 * {@link com.wikantik.render.subsystem.RenderingSubsystem.Services} can
 * carry a properly-typed (but null) slot. Phase 6 Checkpoint 3 lifts the
 * Akismet OkHttp client and the static bot-trap hidden-field validation
 * out of {@code SpamFilter} into a real implementation behind this
 * interface.</p>
 *
 * <p>TODO Phase 6 Ckpt 3: define the operational surface
 * ({@code checkAkismet(Context, String)} / {@code checkBotTrap(Context)}).</p>
 */
public interface SpamExternalSignals {
}

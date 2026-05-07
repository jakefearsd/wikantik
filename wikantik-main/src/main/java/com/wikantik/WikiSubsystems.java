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
package com.wikantik;

import com.wikantik.auth.subsystem.AuthSubsystem;
import com.wikantik.core.subsystem.CoreSubsystem;
import com.wikantik.knowledge.subsystem.KnowledgeSubsystem;
import com.wikantik.page.subsystem.PageSubsystem;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystem;
import com.wikantik.persistence.subsystem.PersistenceSubsystem;
import com.wikantik.render.subsystem.RenderingSubsystem;
import com.wikantik.search.subsystem.SearchSubsystem;
import jakarta.servlet.ServletContext;

/**
 * Bundle of every extracted subsystem's {@code Services} record.
 *
 * <p>Phase 1 of the wikantik-main subsystem decomposition. See
 * {@code docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md}.</p>
 *
 * <p>Stashed on the {@link ServletContext} at engine boot under the
 * {@link #SERVLET_CONTEXT_ATTRIBUTE} attribute so servlets can reach
 * subsystem services without going through the legacy
 * {@code WikiEngine#getManager(Class)} API. The legacy API continues to
 * work during the migration as a bridge; consumers migrate to this bundle
 * one phase at a time.</p>
 *
 * <p>As subsequent phases extract additional subsystems, this record gains
 * fields ({@code core}, {@code persistence}, {@code auth}, {@code page},
 * {@code rendering}, {@code search}, {@code knowledge}, {@code pageGraph}).
 * Adding a field is the trigger to migrate every legacy {@code getManager()}
 * caller for that subsystem's services.</p>
 */
public record WikiSubsystems(
    CoreSubsystem.Services core,
    PersistenceSubsystem.Services persistence,
    AuthSubsystem.Services auth,
    PageSubsystem.Services page,
    RenderingSubsystem.Services rendering,
    SearchSubsystem.Services search,
    KnowledgeSubsystem.Services knowledge,
    PageGraphSubsystem.Services pageGraph
) {

    /** {@link ServletContext} attribute key under which a {@link WikiSubsystems}
     *  bundle is stashed at engine boot. */
    public static final String SERVLET_CONTEXT_ATTRIBUTE = "com.wikantik.WikiSubsystems";
}

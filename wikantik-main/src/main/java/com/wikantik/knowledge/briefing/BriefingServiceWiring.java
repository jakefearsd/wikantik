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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.StructuralIndexService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Single derivation point for the {@link BriefingAssemblyService}, mirroring
 * {@code BundleServiceWiring}: a static {@link #build} that never throws and
 * returns {@code null} when the feature cannot be assembled.
 *
 * <p>The briefing assembler layers on top of the bundle service (for prompt-driven
 * section widening) and the structural index (for cluster expansion). Both are
 * <em>nullable</em>: with them absent the assembler still answers explicit pins in
 * a degraded briefing, so the only hard requirement is a live {@link PageManager}.
 * Collaborators are passed in (not resolved via {@code getManager}) so this stays a
 * plain assembly helper outside the service-locator allow-list.</p>
 *
 * <p>Built at the same post-startup seam as the bundle service —
 * {@code WikiEngine.patchContextRetrievalService} — because it consumes the freshly
 * built {@link BundleAssemblyService}.</p>
 */
public final class BriefingServiceWiring {

    private static final Logger LOG = LogManager.getLogger( BriefingServiceWiring.class );

    private BriefingServiceWiring() {}

    /**
     * Builds a {@link DefaultBriefingAssemblyService}, or {@code null} when the
     * feature is disabled or has no {@link PageManager} to read pages from. Never
     * throws — a missing {@code bundle} or {@code structuralIndex} degrades the
     * relevant lookup (prompt widening / cluster expansion) rather than failing.
     *
     * @param bundle          the live bundle assembly service (null tolerated → prompt widening skipped)
     * @param structuralIndex the structural index (null tolerated → cluster expansion skipped)
     * @param pageManager     page source — required; {@code null} → returns {@code null}
     * @param props           configuration source (null tolerated → defaults)
     */
    public static BriefingAssemblyService build( final BundleAssemblyService bundle,
                                                 final StructuralIndexService structuralIndex,
                                                 final PageManager pageManager,
                                                 final Properties props ) {
        if ( pageManager == null ) {
            LOG.debug( "PageManager not available — briefing assembly service unavailable" );
            return null;
        }
        if ( !BriefingConfig.enabled( props ) ) {
            LOG.info( "Briefing disabled via {}enabled=false — briefing assembly service unavailable",
                BriefingConfig.PREFIX );
            return null;
        }
        LOG.info( "Briefing assembly service wired (bundle={}, structuralIndex={})",
            bundle != null, structuralIndex != null );
        return new DefaultBriefingAssemblyService(
            bundle, structuralIndex, pageManager,
            BriefingConfig.defaultBudget( props ), BriefingConfig.maxBudget( props ) );
    }
}

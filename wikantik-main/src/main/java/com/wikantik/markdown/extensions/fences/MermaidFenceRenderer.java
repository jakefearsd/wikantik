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
package com.wikantik.markdown.extensions.fences;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;

/**
 * Mermaid renderer (browser kind). Runs the fail-closed safety policy server-side: no callbacks,
 * no script-capable link targets, no {@code %%{...}%%} directive of any kind and no front matter.
 * The JS port in {@code wikantik-frontend} mirrors these rules (and is the only gate for a
 * hand-written envelope; see {@link RenderedFenceRenderer}); both are pinned by the shared fixture.
 */
public final class MermaidFenceRenderer implements RenderedFenceRenderer {

    private static final Logger LOG = LogManager.getLogger( MermaidFenceRenderer.class );

    @Override
    public String language() {
        return "mermaid";
    }

    @Override
    public FenceKind kind() {
        return FenceKind.BROWSER;
    }

    @Override
    public FenceVerdict check( final String source ) {
        try {
            return MermaidStatementPolicy.analyse( source ).verdict();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Mermaid fence policy check failed unexpectedly; refusing the fence: {}", e.toString(), e );
            return FenceVerdict.refused( "render-failed" );
        }
    }

    @Override
    public Set< String > pageLinks( final String source ) {
        try {
            final MermaidStatementPolicy.Analysis a = MermaidStatementPolicy.analyse( source );
            return a.verdict().allowed() ? a.pageLinks() : Set.of();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Mermaid page-link extraction failed unexpectedly; reporting no links: {}", e.toString(), e );
            return Set.of();
        }
    }
}

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

import com.wikantik.plantuml.PlantUmlPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * PlantUML renderer (server kind). At parse time it only runs the fail-closed {@link PlantUmlPolicy}; the drawing
 * itself is produced later by the server fence resolver (see {@link RenderedFenceRenderer}).
 */
public final class PlantUmlFenceRenderer implements RenderedFenceRenderer {

    private static final Logger LOG = LogManager.getLogger( PlantUmlFenceRenderer.class );

    @Override
    public String language() {
        return "plantuml";
    }

    @Override
    public FenceKind kind() {
        return FenceKind.SERVER;
    }

    @Override
    public FenceVerdict check( final String source ) {
        try {
            final PlantUmlPolicy.Analysis a = PlantUmlPolicy.analyse( source );
            return a.allowed() ? FenceVerdict.ALLOWED : FenceVerdict.refused( a.reason() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "PlantUML fence policy check failed unexpectedly; refusing the fence: {}", e.toString(), e );
            return FenceVerdict.refused( "render-failed" );
        }
    }

    @Override
    public Set< String > pageLinks( final String source ) {
        try {
            final PlantUmlPolicy.Analysis a = PlantUmlPolicy.analyse( source );
            final Set< String > names = new LinkedHashSet<>();
            if ( a.allowed() ) {
                for ( final String target : a.linkTargets() ) {
                    MermaidUrlRules.pageName( target ).ifPresent( names::add );
                }
            }
            return names;
        } catch ( final RuntimeException e ) {
            LOG.warn( "PlantUML page-link extraction failed unexpectedly; reporting no links: {}", e.toString(), e );
            return Set.of();
        }
    }
}

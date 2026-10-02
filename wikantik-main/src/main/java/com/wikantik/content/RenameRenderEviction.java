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
package com.wikantik.content;

import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;

import java.util.Set;
import java.util.TreeSet;

/**
 * Link existence is baked into cached page HTML, and a rename fires no save event (it moves the file and rewrites
 * referrers with {@code putPageText}): both names and every page that referred to either must be evicted.
 */
final class RenameRenderEviction {

    private RenameRenderEviction() {
    }

    /** Pages referring to {@code name}; taken before the rename, while the new name is still a missing page. */
    static Set< String > referrersOf( final Engine engine, final String name ) {
        final ReferenceManager refs = PageGraphSubsystemBridge.fromLegacyEngine( engine ).referenceManager();
        return refs == null ? Set.of() : new TreeSet<>( refs.findReferrers( name ) );
    }

    static void evict( final Engine engine, final String from, final String to,
                       final Set< String > oldNameReferrers, final Set< String > newNameReferrers ) {
        final RenderingManager rm = RenderingSubsystemBridge.fromLegacyEngine( engine ).renderingManager();
        if ( rm == null ) {
            return;
        }
        final Set< String > pages = new TreeSet<>( oldNameReferrers );
        pages.addAll( newNameReferrers );
        pages.add( from );
        pages.add( to );
        pages.forEach( rm::evictRenderCache );
    }
}

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
package com.wikantik.ontology.runtime;

import com.wikantik.event.KgChangeEvent;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import com.wikantik.event.WikiEventManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bridges {@link KgChangeEvent}s from the two KG write funnels into
 * {@link OntologyEntitySync}. Registered per emitting service instance
 * (the WikiEventManager client object), mirroring how {@link OntologyEventListener}
 * registers on the page/filter managers.
 */
public final class KgChangeEventListener implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( KgChangeEventListener.class );

    private final OntologyEntitySync sync;

    public KgChangeEventListener( final OntologyEntitySync sync ) {
        this.sync = sync;
    }

    /** Attaches to both emitting service instances; null clients are skipped (KG disabled). */
    public void register( final Object kgService, final Object kgMaterialization ) {
        if ( kgService != null ) {
            WikiEventManager.addWikiEventListener( kgService, this );
        }
        if ( kgMaterialization != null ) {
            WikiEventManager.addWikiEventListener( kgMaterialization, this );
        }
        LOG.info( "KG change event listener registered for KnowledgeGraphService + KgMaterializationService events" );
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        if ( event instanceof KgChangeEvent kce ) {
            sync.mark( kce.touchedEntityIds(), kce.removedEntityIds() );
        }
    }
}

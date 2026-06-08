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
package com.wikantik.rest;

import com.wikantik.ontology.OntologyModelManager;
import com.wikantik.ontology.runtime.OntologyRebuildCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Base for the public read-only RDF endpoints: model accessor + permissive (credential-less) CORS. */
public abstract class PublicRdfServletBase extends RestServletBase {

    private static final long serialVersionUID = 1L;

    /** The materialized PUBLIC dataset manager, or null if the ontology runtime is unavailable. */
    protected OntologyModelManager modelManager() {
        final OntologyRebuildCoordinator coordinator = getSubsystems().pageGraph().ontologyRebuildCoordinator();
        return coordinator == null ? null : coordinator.modelManager();
    }

    /** Fully public, credential-less CORS — these endpoints carry no session. */
    @Override
    protected void setCorsHeaders( final HttpServletRequest request, final HttpServletResponse response ) {
        response.setHeader( "Access-Control-Allow-Origin", "*" );
        response.setHeader( "Access-Control-Allow-Methods", "GET, POST, OPTIONS" );
        response.setHeader( "Access-Control-Allow-Headers", "Accept, Content-Type" );
    }
}

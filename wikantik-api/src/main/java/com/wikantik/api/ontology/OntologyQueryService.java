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
package com.wikantik.api.ontology;

import java.util.List;

/**
 * Read-only ontology queries for retrieval-time query expansion. Pure Java types only
 * (no Jena) so it can live in {@code wikantik-api} and be consumed by the retriever
 * without a module cycle. Implemented by {@code JenaOntologyQueryService} in
 * {@code wikantik-ontology}.
 */
public interface OntologyQueryService {

    /**
     * Expansion terms for a free-text query: labels of transitive subclasses of any class
     * the query names, plus labels of SKOS-narrower concepts of any concept it names.
     * Deduplicated, excludes terms already present in the query, empty when nothing matches.
     *
     * @param query the user's free-text query (may be {@code null}/blank)
     * @return additional terms to append to the query, never {@code null}
     */
    List< String > expandQuery( String query );
}

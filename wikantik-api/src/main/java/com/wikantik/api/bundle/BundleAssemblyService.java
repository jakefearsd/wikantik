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
package com.wikantik.api.bundle;

/** RAG-as-a-Service: assemble retrieval into a ranked, de-duplicated, cited context bundle.
 *  No answer synthesis (ADR-0001). */
public interface BundleAssemblyService {
    ContextBundle assemble( String query );

    /**
     * Assemble a bundle using the given retrieval mode. Default implementation ignores
     * mode and delegates to {@link #assemble(String)}; the production implementation
     * selects a per-mode candidate source.
     */
    default ContextBundle assemble( String query, RetrievalMode mode ) {
        return assemble( query );
    }

    /**
     * Assemble a bundle with per-request overrides. The default implementation honours only
     * {@link BundleOptions#mode()} (delegating to {@link #assemble(String, RetrievalMode)} when set,
     * else {@link #assemble(String)}) and ignores {@link BundleOptions#maxSections()}; the
     * production implementation applies both.
     */
    default ContextBundle assemble( String query, BundleOptions options ) {
        return options == null || options.mode() == null ? assemble( query ) : assemble( query, options.mode() );
    }
}

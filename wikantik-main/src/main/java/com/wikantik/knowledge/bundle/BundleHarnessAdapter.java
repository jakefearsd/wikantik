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
package com.wikantik.knowledge.bundle;

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.knowledge.eval.BundleEvalRunner;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Bridges {@link BundleAssemblyService} onto the Phase-0 eval harness as a
 * {@link BundleEvalRunner.BundleRetriever}. Calls {@code assemble(query)} and
 * maps each {@link com.wikantik.api.bundle.BundleSection} to its
 * {@link com.wikantik.api.eval.BundleSection} counterpart, preserving
 * {@code canonicalId}, {@code headingPath}, and {@code text}.
 */
public final class BundleHarnessAdapter implements BundleEvalRunner.BundleRetriever {

    private final BundleAssemblyService service;

    public BundleHarnessAdapter( final BundleAssemblyService service ) {
        this.service = service;
    }

    @Override
    public List< com.wikantik.api.eval.BundleSection > apply( final String query ) {
        return service.assemble( query ).sections().stream()
            .map( b -> new com.wikantik.api.eval.BundleSection(
                b.canonicalId(), b.headingPath(), b.text() ) )
            .collect( Collectors.toList() );
    }
}

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
package com.wikantik.insights;

import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link PageFacts} test double. Backed by a plain {@link Map} keyed on
 * {@code pagePath} so tests can set up exactly the facts a rule under test needs, with no wiki
 * engine involved.
 */
public class MapPageFacts implements PageFacts {

    private final Map<String, PageFact> facts;

    /**
     * Creates a test double backed by the given facts, keyed by {@link PageFact#pagePath()}.
     *
     * @param facts the page facts to serve, keyed by page path
     */
    public MapPageFacts( final Map<String, PageFact> facts ) {
        this.facts = facts;
    }

    @Override
    public Optional<PageFact> lookup( final String pagePath ) {
        return Optional.ofNullable( facts.get( pagePath ) );
    }
}

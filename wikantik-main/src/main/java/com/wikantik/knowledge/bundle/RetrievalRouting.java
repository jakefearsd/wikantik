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

import com.wikantik.api.bundle.RetrievalMode;

import java.util.Map;

/**
 * Per-{@link RetrievalMode} candidate-source wiring: which {@link SectionCandidateSource} to
 * route a query to, and which mode to fall back to when the requested mode has no wired source.
 * {@code defaultMode} must itself be a key of {@code sources} — enforced by the
 * {@link DefaultBundleAssemblyService} canonical constructor that consumes this record.
 */
record RetrievalRouting( Map< RetrievalMode, SectionCandidateSource > sources, RetrievalMode defaultMode ) {}

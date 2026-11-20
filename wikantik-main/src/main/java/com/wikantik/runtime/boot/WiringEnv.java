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
package com.wikantik.runtime.boot;

import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.cache.CachingManager;
import com.wikantik.content.PageRenamer;
import com.wikantik.filters.FilterManager;
import com.wikantik.search.SearchManager;
import io.micrometer.core.instrument.MeterRegistry;

import javax.sql.DataSource;
import java.util.Properties;

/**
 * The collaborators a {@link BootSequence}'s steps read, resolved once before the first step starts.
 *
 * <p>Resolving them up front keeps every service-locator lookup in the one approved method that builds this record
 * (DecompositionArchTest freezes {@code WikiEngine#getManager} callers by method, and a lookup made inside a step
 * lambda would count as a new caller). Any component may be {@code null} when its subsystem did not start; each
 * step tolerates that exactly as the wiring it calls does.</p>
 *
 * @param props             the engine properties
 * @param dataSource        the resolved JNDI datasource
 * @param searchManager     the search manager
 * @param meterRegistry     the shared meter registry, or {@code null} when none is installed
 * @param pageManager       the page manager
 * @param filterManager     the page-filter chain
 * @param referenceManager  the reference manager
 * @param cachingManager    the caching manager
 * @param attachmentManager the attachment manager
 * @param pageRenamer       the page renamer
 */
public record WiringEnv(
    Properties props,
    DataSource dataSource,
    SearchManager searchManager,
    MeterRegistry meterRegistry,
    PageManager pageManager,
    FilterManager filterManager,
    ReferenceManager referenceManager,
    CachingManager cachingManager,
    AttachmentManager attachmentManager,
    PageRenamer pageRenamer ) {
}

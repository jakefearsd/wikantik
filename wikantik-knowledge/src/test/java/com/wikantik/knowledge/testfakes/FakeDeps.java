/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.wikantik.knowledge.testfakes;

import com.wikantik.api.core.Engine;
import com.wikantik.knowledge.DefaultContextRetrievalService;

/** Test-only builder that wires null-tolerant deps into the service. */
public final class FakeDeps {

    private Engine engine = FakeEngine.create();
    private FakeSearchManager search = new FakeSearchManager();
    private FakePageManager pageManager = new FakePageManager();
    private String baseUrl = "";

    public static FakeDeps minimal() { return new FakeDeps(); }

    public FakeDeps engine( final Engine e ) { this.engine = e; return this; }
    public FakeDeps search( final FakeSearchManager s ) { this.search = s; return this; }
    public FakeDeps pageManager( final FakePageManager pm ) { this.pageManager = pm; return this; }
    public FakeDeps baseUrl( final String u ) { this.baseUrl = u; return this; }

    public DefaultContextRetrievalService build() {
        return new DefaultContextRetrievalService(
            engine, search, null, null, null, null, null, null, pageManager, null, baseUrl );
    }
}

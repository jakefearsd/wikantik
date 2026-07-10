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
package com.wikantik.knowledge.eval;

import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.ContextBundle;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BundleEvalWiringTest {

    private static BundleAssemblyService stubBundle() {
        return new BundleAssemblyService() {
            @Override public ContextBundle assemble( final String q ) { return new ContextBundle( q, java.util.List.of(), null ); }
            @Override public ContextBundle assemble( final String q, final com.wikantik.api.bundle.RetrievalMode m ) { return assemble( q ); }
        };
    }

    @Test
    void build_nullBundleService_returnsNull() {
        assertNull( BundleEvalWiring.build( null, null, new Properties(), s -> java.util.Optional.empty() ) );
    }

    @Test
    void build_intervalZero_returnsDisabledScheduler_notNull() {
        // A scheduler is still constructed (so it can be stopped), just never scheduled.
        final Properties p = new Properties();  // interval defaults to 0
        final BundleEvalScheduler s = BundleEvalWiring.build( stubBundle(), null, p, slug -> java.util.Optional.of( "CID" ) );
        assertNotNull( s );
        s.start();  // must be a no-op, no throw
        s.stop();
    }
}

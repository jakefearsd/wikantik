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
package com.wikantik.rest.overview;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class OverviewAssemblerTest {
    @Test
    void healthyCollectorsLandUnderTheirKeys() {
        final Map<String, Supplier<JsonObject>> collectors = new LinkedHashMap<>();
        collectors.put( "load", () -> { final JsonObject o = new JsonObject(); o.addProperty( "inflight", 3 ); return o; } );
        final OverviewSnapshot snap = new OverviewAssembler( collectors ).assemble();
        assertTrue( snap.cards().has( "load" ) );
        assertEquals( 3, snap.cards().getAsJsonObject( "load" ).get( "inflight" ).getAsInt() );
        assertTrue( snap.degraded().isEmpty() );
    }

    @Test
    void aThrowingCollectorDegradesOnlyItsOwnCard() {
        final Map<String, Supplier<JsonObject>> collectors = new LinkedHashMap<>();
        collectors.put( "ok", JsonObject::new );
        collectors.put( "boom", () -> { throw new IllegalStateException( "kaboom" ); } );
        final OverviewSnapshot snap = new OverviewAssembler( collectors ).assemble();
        assertTrue( snap.cards().has( "ok" ) );
        assertFalse( snap.cards().has( "boom" ) );
        assertEquals( java.util.List.of( "boom" ), snap.degraded() );
    }
}

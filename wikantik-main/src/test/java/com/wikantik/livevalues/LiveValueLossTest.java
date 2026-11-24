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
package com.wikantik.livevalues;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveValueLossTest {

    @Test
    void droppedTokenIsReported() {
        assertEquals( List.of( "{{wiki:articles}}" ),
                LiveValueLoss.lostTokens( "holds {{wiki:articles}} pages", "holds 1,100 pages" ) );
        assertEquals( 1, LiveValueLoss.warnings( "{{wiki:articles}}", "1100" ).size() );
        assertTrue( LiveValueLoss.warnings( "{{wiki:articles}}", "1100" ).get( 0 ).message().contains( "{{wiki:articles}}" ) );
    }

    @Test
    void keptTokensAndTokenFreePagesWarnNothing() {
        assertTrue( LiveValueLoss.warnings( "{{wiki:articles}} {{wiki:pages}}", "{{wiki:pages}} x {{wiki:articles}}" ).isEmpty() );
        assertTrue( LiveValueLoss.warnings( "plain", "still plain" ).isEmpty() );
        assertTrue( LiveValueLoss.warnings( null, "{{wiki:articles}}" ).isEmpty() );
        assertTrue( LiveValueLoss.warnings( "plain", "{{wiki:articles}}" ).isEmpty(), "adding a token loses nothing" );
    }

    @Test
    void comparesAsAMultisetOfTokenText() {
        assertEquals( List.of( "{{wiki:articles}}" ),
                LiveValueLoss.lostTokens( "{{wiki:articles}} {{wiki:articles}}", "{{wiki:articles}}" ) );
        assertEquals( List.of( "{{wiki:articles|round=100}}" ),
                LiveValueLoss.lostTokens( "{{wiki:articles|round=100}}", "{{wiki:articles}}" ) );
    }
}

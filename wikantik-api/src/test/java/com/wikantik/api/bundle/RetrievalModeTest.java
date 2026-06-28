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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalModeTest {
    @Test void fromWire_parses_each_valid_value_case_insensitively() {
        assertEquals( RetrievalMode.HYBRID,  RetrievalMode.fromWire( "hybrid" ) );
        assertEquals( RetrievalMode.DENSE,   RetrievalMode.fromWire( "DENSE" ) );
        assertEquals( RetrievalMode.LEXICAL, RetrievalMode.fromWire( "Lexical" ) );
    }
    @Test void fromWire_null_or_blank_defaults_to_hybrid() {
        assertEquals( RetrievalMode.HYBRID, RetrievalMode.fromWire( null ) );
        assertEquals( RetrievalMode.HYBRID, RetrievalMode.fromWire( "  " ) );
    }
    @Test void fromWire_invalid_throws_listing_valid_values() {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
            () -> RetrievalMode.fromWire( "fuzzy" ) );
        assertTrue( e.getMessage().contains( "hybrid" ) && e.getMessage().contains( "dense" )
            && e.getMessage().contains( "lexical" ), e.getMessage() );
    }
}

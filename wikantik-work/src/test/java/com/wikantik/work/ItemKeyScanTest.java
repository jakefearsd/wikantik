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
package com.wikantik.work;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemKeyScanTest {

    @Test
    void findsKeysInBranchesAndMessages() {
        assertEquals( List.of( "PLAT-15" ), ItemKeyScan.keys( "PLAT-15-fix-login" ) );
        assertEquals( List.of( "PLAT-15" ), ItemKeyScan.keys( "feature/PLAT-15" ) );
        assertEquals( List.of( "PLAT-15", "OPS-2" ), ItemKeyScan.keys( "Fix PLAT-15 and OPS-2" ) );
        assertEquals( List.of( "PLAT-15" ), ItemKeyScan.keys( "PLAT-15 PLAT-15" ) );
    }

    @Test
    void aLongerPrefixIsItsOwnKeyAndLowerCaseOrZeroIsNone() {
        assertEquals( List.of( "XPLAT-15" ), ItemKeyScan.keys( "XPLAT-15" ) );
        assertEquals( List.of(), ItemKeyScan.keys( "plat-15" ) );
        assertEquals( List.of(), ItemKeyScan.keys( "PLAT-0" ) );
        assertEquals( List.of(), ItemKeyScan.keys( null ) );
    }

    @Test
    void theScanReturnsUtf8AndTheApplierIgnoresIt() {
        assertEquals( List.of( "UTF-8" ), ItemKeyScan.keys( "encode as UTF-8" ) );
    }

    @Test
    void atMostTenKeys() {
        final String text = String.join( " ", IntStream.rangeClosed( 1, 12 ).mapToObj( i -> "AB-" + i ).toList() );
        final List< String > keys = ItemKeyScan.keys( text );
        assertEquals( 10, keys.size() );
        assertEquals( "AB-1", keys.get( 0 ) );
        assertEquals( "AB-10", keys.get( 9 ) );
    }

    @Test
    void reviewNumbersComeFromSquashAndMergeMessages() {
        assertEquals( Set.of( "12" ), ItemKeyScan.reviewNumbers( "Fix login (#12)" ) );
        assertEquals( Set.of( "12" ), ItemKeyScan.reviewNumbers( "Merge pull request #12 from o/PLAT-15-x" ) );
        assertEquals( Set.of(), ItemKeyScan.reviewNumbers( null ) );
    }
}

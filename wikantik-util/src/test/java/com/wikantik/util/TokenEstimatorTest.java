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

package com.wikantik.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenEstimatorTest {

    @Test
    void fourCharsPerTokenCeiling() {
        assertEquals( 0, TokenEstimator.estimate( null ) );
        assertEquals( 0, TokenEstimator.estimate( "" ) );
        assertEquals( 1, TokenEstimator.estimate( "abc" ) );
        assertEquals( 1, TokenEstimator.estimate( "abcd" ) );
        assertEquals( 2, TokenEstimator.estimate( "abcde" ) );
        assertEquals( 25, TokenEstimator.estimate( "x".repeat( 100 ) ) );
    }
}

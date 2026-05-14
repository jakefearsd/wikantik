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
package com.wikantik.kgpolicy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class KgInclusionFilterBypassTest {

    @Test
    void nodeFilterReturnsExistingFragmentsWhenBypassIsFalse() {
        assertEquals( KgInclusionFilter.NODE_FILTER_JOIN, KgInclusionFilter.nodeFilterJoin( false ) );
        assertEquals( KgInclusionFilter.NODE_FILTER_WHERE, KgInclusionFilter.nodeFilterWhere( false ) );
    }

    @Test
    void nodeFilterReturnsEmptyAndTrueWhenBypassIsTrue() {
        assertEquals( "", KgInclusionFilter.nodeFilterJoin( true ) );
        assertEquals( " TRUE ", KgInclusionFilter.nodeFilterWhere( true ) );
    }

    @Test
    void edgeFilterRespectsBypass() {
        assertEquals( KgInclusionFilter.EDGE_FILTER_JOIN, KgInclusionFilter.edgeFilterJoin( false ) );
        assertEquals( KgInclusionFilter.EDGE_FILTER_WHERE, KgInclusionFilter.edgeFilterWhere( false ) );
        assertEquals( "", KgInclusionFilter.edgeFilterJoin( true ) );
        assertEquals( " TRUE ", KgInclusionFilter.edgeFilterWhere( true ) );
    }

    @Test
    void mentionFilterRespectsBypass() {
        assertEquals( KgInclusionFilter.MENTION_FILTER_JOIN, KgInclusionFilter.mentionFilterJoin( false ) );
        assertEquals( KgInclusionFilter.MENTION_FILTER_WHERE, KgInclusionFilter.mentionFilterWhere( false ) );
        assertEquals( "", KgInclusionFilter.mentionFilterJoin( true ) );
        assertEquals( " TRUE ", KgInclusionFilter.mentionFilterWhere( true ) );
    }
}

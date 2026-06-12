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
package com.wikantik.knowledge.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared {@code limit}/{@code offset} input-schema fields for the paginated list tools. */
final class PaginationSchema {

    private PaginationSchema() { }

    /** A fresh, mutable property map carrying the standard {@code limit} + {@code offset} fields. */
    static Map< String, Object > props() {
        final Map< String, Object > p = new LinkedHashMap<>();
        p.put( "limit", Map.of(
                "type", "integer",
                "description", "Max items to return (default 50). The true total is in 'count' and "
                        + "'hasMore' signals there are more — page with 'offset'.",
                "examples", List.of( 50 ) ) );
        p.put( "offset", Map.of(
                "type", "integer",
                "description", "Number of items to skip, for paging (default 0).",
                "examples", List.of( 0 ) ) );
        return p;
    }
}

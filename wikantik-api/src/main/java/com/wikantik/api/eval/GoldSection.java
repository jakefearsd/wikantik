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
package com.wikantik.api.eval;

import java.util.List;

/**
 * The section a question is expected to be answered from: a page canonical_id +
 * its section heading-path. Section-level (not chunk-level) so it stays valid
 * across re-chunking and re-extraction.
 */
public record GoldSection( String canonicalId, List< String > headingPath ) {
    public GoldSection {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId must not be blank" );
        }
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
    }
}

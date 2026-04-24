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
package com.wikantik.api.knowledge;

import java.util.List;

/**
 * Output of {@link ContextRetrievalService#retrieve(ContextQuery)}. Echoes
 * the query back for client convenience. {@code totalMatched} is the count
 * of pages considered before truncation to {@code maxPages}.
 */
public record RetrievalResult( String query, List< RetrievedPage > pages, int totalMatched ) {
    public RetrievalResult {
        if ( query == null ) {
            throw new IllegalArgumentException( "query must not be null" );
        }
        if ( pages == null ) {
            throw new IllegalArgumentException( "pages must not be null" );
        }
        if ( totalMatched < 0 ) {
            throw new IllegalArgumentException( "totalMatched must not be negative" );
        }
        pages = List.copyOf( pages );
    }
}

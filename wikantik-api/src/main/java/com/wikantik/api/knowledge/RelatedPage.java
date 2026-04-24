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

/**
 * Hint that another wiki page is closely related to a retrieved page via
 * Knowledge Graph mention co-occurrence. {@code reason} is a human-readable
 * summary of why (e.g. {@code "shared entities: qwen3, bm25"}) — may be empty
 * when the service cannot populate it, never {@code null}.
 */
public record RelatedPage( String name, String reason ) {
    public RelatedPage {
        if ( name == null || name.isBlank() ) {
            throw new IllegalArgumentException( "name must not be blank" );
        }
        reason = reason == null ? "" : reason;
    }
}

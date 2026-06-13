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
 * One evidence section as it appears in an assembled context bundle: the source
 * page canonical_id, the section heading-path, and the section text. The unit
 * the bundle metrics score against.
 */
public record BundleSection( String canonicalId, List< String > headingPath, String text ) {
    public BundleSection {
        if ( canonicalId == null || canonicalId.isBlank() ) {
            throw new IllegalArgumentException( "canonicalId must not be blank" );
        }
        if ( text == null ) {
            throw new IllegalArgumentException( "text must not be null" );
        }
        headingPath = headingPath == null ? List.of() : List.copyOf( headingPath );
    }
}

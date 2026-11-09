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
package com.wikantik.ontology;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.wikantik.ontology.projection.PageRecord;

/**
 * Pure ACL split: selects the pages that may appear in the PUBLIC ontology dataset — a
 * page-record iff its slug is anonymously viewable. No auth here — the caller supplies the
 * anonymous-view predicate.
 */
public final class PublicProjectionFilter {

    private PublicProjectionFilter() {}

    public static List< PageRecord > publicPages( final List< PageRecord > pages, final Predicate< String > isPublic ) {
        return pages.stream()
                .filter( p -> p.slug() != null && isPublic.test( p.slug() ) )
                .collect( Collectors.toList() );
    }
}

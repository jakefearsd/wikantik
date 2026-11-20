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
package com.wikantik.insights;

/** The suggested action shown for each jakemon detector type. */
final class ImportedOpportunityActions {

    private ImportedOpportunityActions() {
    }

    /** One short suggested action per jakemon detector type (design §7.3 "Imported from jakemon"). */
    static String suggestedAction( final String type ) {
        return switch ( type ) {
            case "striking_distance" ->
                    "Page-2 query with real demand -- a small rank push wins the most clicks.";
            case "ctr_gap" -> "Ranks well but converts badly -- rewrite the title/meta snippet.";
            case "content_gap" ->
                    "Demand exists and no strong page covers it -- write or strengthen a page for this query.";
            case "cannibalization" ->
                    "Multiple pages split this query's equity -- consolidate or differentiate them.";
            case "decay" -> "Average position worsened materially against the prior window -- investigate.";
            default -> "Review this imported opportunity.";
        };
    }
}

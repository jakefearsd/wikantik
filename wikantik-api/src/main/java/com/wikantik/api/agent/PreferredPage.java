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
package com.wikantik.api.agent;

/**
 * Single entry of {@link AgentHintsBlock#prefer_pages()}. {@code title} is
 * carried explicitly so an agent reading a {@code /for-agent} projection can
 * decide whether to fetch the referenced page without a second round-trip.
 *
 * <p>{@code role} is one of {@code cluster_hub}, {@code authoritative_reference},
 * or {@code cluster_member}. Snake_case Java field names so default Gson
 * serialisation matches the wire form (mirrors {@link RunbookBlock} convention).
 * </p>
 */
@SuppressWarnings( "checkstyle:MemberName" )
public record PreferredPage( String canonical_id, String title, String role ) {
    public PreferredPage {
        if ( canonical_id == null || canonical_id.isBlank() ) {
            throw new IllegalArgumentException( "canonical_id required" );
        }
        if ( title == null || title.isBlank() ) {
            throw new IllegalArgumentException( "title required" );
        }
        if ( role == null || role.isBlank() ) {
            role = "cluster_member";
        }
    }
}

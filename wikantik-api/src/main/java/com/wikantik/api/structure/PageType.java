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
package com.wikantik.api.structure;

/**
 * Canonical page type as declared in frontmatter {@code type:}. {@link #UNKNOWN}
 * captures pages that either omit the field or declare a value not yet in the
 * supported vocabulary.
 */
public enum PageType {
    HUB, ARTICLE, REFERENCE, RUNBOOK, DESIGN, UNKNOWN;

    public static PageType fromFrontmatter( final Object raw ) {
        if ( raw == null ) {
            return UNKNOWN;
        }
        final String value = raw.toString().trim().toLowerCase( java.util.Locale.ROOT );
        return switch ( value ) {
            case "hub"       -> HUB;
            case "article"   -> ARTICLE;
            case "reference" -> REFERENCE;
            case "runbook"   -> RUNBOOK;
            case "design"    -> DESIGN;
            default          -> UNKNOWN;
        };
    }

    public String asFrontmatterValue() {
        return name().toLowerCase( java.util.Locale.ROOT );
    }
}

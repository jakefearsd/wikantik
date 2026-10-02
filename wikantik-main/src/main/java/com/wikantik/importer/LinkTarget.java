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
package com.wikantik.importer;

/**
 * Outcome of resolving a vault link target. {@code RENAME} carries the replacement ({@code value}), {@code KEEP}
 * means leave the link as written, {@code UNRESOLVED} means nothing in the vault or wiki matches.
 */
public record LinkTarget( Kind kind, String value ) {

    /** Resolution outcome. */
    public enum Kind { RENAME, KEEP, UNRESOLVED }

    private static final LinkTarget KEEP_TARGET = new LinkTarget( Kind.KEEP, null );
    private static final LinkTarget UNRESOLVED_TARGET = new LinkTarget( Kind.UNRESOLVED, null );

    /** A target that must be rewritten to {@code v}. */
    public static LinkTarget rename( final String v ) {
        return new LinkTarget( Kind.RENAME, v );
    }

    /** A target to leave as written. */
    public static LinkTarget keep() {
        return KEEP_TARGET;
    }

    /** A target that did not resolve. */
    public static LinkTarget unresolved() {
        return UNRESOLVED_TARGET;
    }
}

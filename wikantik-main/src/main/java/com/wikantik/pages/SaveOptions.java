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
package com.wikantik.pages;

import java.util.Map;

/**
 * Immutable configuration for a page save operation via {@link PageSaveHelper}.
 */
public record SaveOptions(
        String author,
        String changeNote,
        String markupSyntax,
        int expectedVersion,
        String expectedContentHash,
        Map< String, Object > metadata,
        boolean replaceMetadata
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String author;
        private String changeNote;
        private String markupSyntax;
        private int expectedVersion = -1;
        private String expectedContentHash;
        private Map< String, Object > metadata;
        private boolean replaceMetadata;

        public Builder author( final String a ) { this.author = a; return this; }
        public Builder changeNote( final String c ) { this.changeNote = c; return this; }
        public Builder markupSyntax( final String m ) { this.markupSyntax = m; return this; }
        public Builder expectedVersion( final int v ) { this.expectedVersion = v; return this; }
        public Builder expectedContentHash( final String h ) { this.expectedContentHash = h; return this; }
        public Builder metadata( final Map< String, Object > m ) { this.metadata = m; return this; }
        public Builder replaceMetadata( final boolean r ) { this.replaceMetadata = r; return this; }

        public SaveOptions build() {
            return new SaveOptions( author, changeNote, markupSyntax, expectedVersion,
                    expectedContentHash, metadata, replaceMetadata );
        }
    }
}

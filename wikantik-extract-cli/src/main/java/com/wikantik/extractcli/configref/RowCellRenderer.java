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
package com.wikantik.extractcli.configref;

import com.wikantik.util.config.ConfigReference;

/**
 * Per-row table-cell rendering (Type / Default / Override columns). Split out of
 * {@link GenerateConfigReferenceCli} (2026-09, complexity burn-down).
 */
final class RowCellRenderer {

    private RowCellRenderer() {}

    /**
     * The Type cell, e.g. {@code enum(a|b|c)}, must not corrupt the Markdown table: the {@code |}
     * separators inside an enum value are indistinguishable from real cell delimiters to a GFM
     * parser. Backticks alone are not sufficient — not every renderer honours "no special meaning
     * inside code spans" for table-cell splitting — so the pipe is escaped as well.
     */
    static String typeCell( final ConfigReference.Entry e ) {
        final String type = e.type() == null ? "" : e.type();
        return "`" + type.replace( "|", "\\|" ) + "`";
    }

    static String defaultCell( final ConfigReference.Entry e ) {
        if ( !e.isBlank() ) {
            return "`" + e.value() + "`";
        }
        if ( "secret".equals( e.type() ) ) {
            return "*(blank)*";
        }
        if ( e.blankMeans() != null && !e.blankMeans().isBlank() ) {
            return "*(blank: " + e.blankMeans() + ")*";
        }
        return "*(blank)*";
    }

    /**
     * @param fixedOverrideText when non-null (see {@link GenerateConfigReferenceCli.SourceFile#fixedOverrideText()}),
     *                          rendered verbatim instead of the entry's env-var name
     */
    static String overrideCell( final ConfigReference.Entry e, final String fixedOverrideText ) {
        if ( fixedOverrideText != null ) {
            return fixedOverrideText;
        }
        if ( "system-property".equals( e.source() ) ) {
            return "`-D" + e.key() + "` only";
        }
        return "`" + e.envOverrideName() + "`";
    }
}

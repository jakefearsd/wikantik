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
package com.wikantik.markdown.extensions.blockids;

import java.util.regex.Pattern;

/**
 * For tests about something other than block references that compare exact rendered HTML: every rendered block carries
 * a {@code data-block} ordinal ({@link BlockIdPostProcessor}), which {@link #strip} takes out. The ordinals themselves
 * are pinned by {@code BlockIdRenderTest}.
 */
public final class BlockOrdinals {

    private static final Pattern ORDINAL = Pattern.compile( " data-block=\"\\d+\"" );

    private BlockOrdinals() {
    }

    /** {@code html} without its {@code data-block} attributes ({@code null} stays {@code null}). */
    public static String strip( final String html ) {
        return html == null ? null : ORDINAL.matcher( html ).replaceAll( "" );
    }
}

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
package com.wikantik.render;

import com.wikantik.api.core.Context;
import com.wikantik.auth.acl.AclDirectiveText;

/**
 * The text a display render parses: the page text less every access rule the ACL manager enforces
 * ({@link AclDirectiveText#strip}). Removed for every viewer alike, editors included, so a render stays one deterministic
 * function of the page text and the principal-less render caches stay safe; and with no rule left to parse, a display
 * render never sets a page's ACL — the ACL manager's text scan is its only source. The WYSIWYG editor mode is the
 * exception: it renders the markup back for editing, rules included.
 */
final class DisplayText {

    private DisplayText() {
    }

    /**
     * @param context  the render context
     * @param pagedata the raw text (frontmatter included, or a body)
     * @return the text to parse for display
     */
    static String of( final Context context, final String pagedata ) {
        return Boolean.TRUE.equals( context.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE ) )
                ? pagedata : AclDirectiveText.strip( pagedata );
    }

    /**
     * For {@code textToHTML}'s explicit form: a render ({@code justParse == false}) shows the text, so it parses it as
     * {@link #of} says; a bare parse reads the raw text and honours its caller's access-rule switch.
     *
     * @param context   the render context
     * @param pagedata  the raw text
     * @param justParse whether the caller only parses (for its link hooks) and renders nothing
     * @return the text to parse
     */
    static String forParse( final Context context, final String pagedata, final boolean justParse ) {
        return justParse ? pagedata : of( context, pagedata );
    }
}

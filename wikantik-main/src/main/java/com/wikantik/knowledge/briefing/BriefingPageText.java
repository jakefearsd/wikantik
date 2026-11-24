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
package com.wikantik.knowledge.briefing;

import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.livevalues.LiveFrontmatter;
import com.wikantik.parser.markdown.ReaderText;

/**
 * Page text for injection into an agent's context: derived text (spec 1.11a), where access-rule directives (people, by
 * login), {@code %%comments%%} and block markers are left out, and live-value tokens show their values.
 */
final class BriefingPageText {

    private BriefingPageText() {
    }

    /** @param liveValues nullable; null leaves tokens verbatim */
    static String of( final PageManager pageManager, final String slug, final LiveValues liveValues ) {
        return LiveFrontmatter.expandRawText(
            ReaderText.derived( AclDirectiveText.strip( pageManager.getPureText( slug, PageProvider.LATEST_VERSION ) ) ),
            liveValues );
    }
}

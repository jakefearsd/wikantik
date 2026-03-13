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
package org.apache.wiki.plugin;

import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.exceptions.PluginException;
import org.apache.wiki.api.plugin.Plugin;

import java.util.Map;

/**
 * Sets a page alias so that viewing the page redirects to the target page.
 *
 * <p>Usage: {@code [{ALIAS target-page}]}
 *
 * <p>This is equivalent to {@code [{SET alias='target-page'}]} and exists for
 * backwards compatibility with older JSPWiki content.
 *
 * @since 3.0.7
 */
public class AliasPlugin implements Plugin {

    @Override
    public String execute( final Context context, final Map<String, String> params ) throws PluginException {
        final String targetPage = params.get( PluginManager.PARAM_CMDLINE );
        if ( targetPage == null || targetPage.trim().isEmpty() ) {
            throw new PluginException( "ALIAS plugin requires a target page name" );
        }

        final Page page = context.getRealPage();
        page.setAttribute( Page.ALIAS, targetPage.trim() );

        return "";
    }
}

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
package com.wikantik.plugin;

import com.wikantik.api.core.Context;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.plugin.Plugin;
import com.wikantik.preferences.Preferences;
import com.wikantik.references.ReferenceManager;
import com.wikantik.util.TextUtil;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 *  <p>Lists all pages containing links to Undefined Pages (pages containing dead links).</p>
 *
 *  An original idea from Gregor Hagedorn.
 *
 *  @since 2.10.0
 */
public class ReferringUndefinedPagesPlugin extends AbstractReferralPlugin {

    /** Parameter name for setting the maximum items to show.  Value is <tt>{@value}</tt>. */
    public static final String PARAM_MAX = "max";

    /** Parameter name for setting the text to show when the maximum items is overruled. Value is <tt>{@value}</tt>. */
    public static final String PARAM_EXTRAS = "extras";

    @Override
    public String execute( final Context context, final Map<String, String> params) throws PluginException {
        final ResourceBundle rb = Preferences.getBundle(context, Plugin.CORE_PLUGINS_RESOURCEBUNDLE);
        final ReferenceManager referenceManager = context.getEngine().getManager( ReferenceManager.class );

        final int items = TextUtil.parseIntParameter(params.get(PARAM_MAX), ALL_ITEMS);
        String extras = params.get(PARAM_EXTRAS);
        if (extras == null) {
            extras = rb.getString("referringundefinedpagesplugin.more");
        }

        final Collection< String > uncreatedPages = referenceManager.findUncreated();
        super.initialize( context, params );

        final TreeMap< String, String > sortedMap;
        sortedMap = uncreatedPages.stream().map(referenceManager::findReferrers).flatMap(Collection::stream).collect(Collectors.toMap(referringPage -> referringPage, referringPage -> "", (a, b) -> b, TreeMap::new));

        Collection< String > result = super.filterAndSortCollection( sortedMap.keySet() );

        final String wikitext = wikitizeCollection( result, separator, items );
        final StringBuilder resultHTML = new StringBuilder();
        resultHTML.append( applyColumnsStyle( makeHTML( context, wikitext ) ) );

        // add the more.... text
        if( items < result.size() && items > 0 ) {
            final Object[] args = { "" + ( result.size() - items ) };
            extras = MessageFormat.format( extras, args );

            resultHTML.append( "<br/>" ).append( extras ).append( "<br/>" );
        }
        return resultHTML.toString();
    }

}

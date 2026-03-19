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
package com.wikantik.markdown;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.engine.EngineLifecycleExtension;

import java.util.Properties;


/**
 * {@link EngineLifecycleExtension} that sets up all the relevant properties to enable markdown syntax if the
 * {@code wikantik.syntax} property has been given, with the {@code markdown} value.
 */
public class MarkdownSetupEngineLifecycleExtension implements EngineLifecycleExtension {

    private static final Logger LOG = LogManager.getLogger( MarkdownSetupEngineLifecycleExtension.class );

    /** {@inheritDoc} */
    @Override
    public void onInit( final Properties properties ) {
        if( "markdown".equalsIgnoreCase( properties.getProperty( "wikantik.syntax" ) ) ) {
            setWikiProperty( properties, "wikantik.renderingManager.markupParser", "com.wikantik.parser.markdown.MarkdownParser" );
            setWikiProperty( properties, "wikantik.renderingManager.renderer", "com.wikantik.render.markdown.MarkdownRenderer" );
            setWikiProperty( properties, "wikantik.renderingManager.renderer.wysiwyg", "com.wikantik.render.markdown.MarkdownRenderer" );
            setWikiProperty( properties, "wikantik.syntax.plain", "plain/wiki-snips-markdown.js" );
        }
    }

    void setWikiProperty( final Properties properties, final String key, final String value ) {
        properties.setProperty( key, value );
        LOG.info( "{} set to {}", key, value );
    }

}

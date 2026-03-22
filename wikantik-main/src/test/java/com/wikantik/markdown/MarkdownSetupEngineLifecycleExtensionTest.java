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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

class MarkdownSetupEngineLifecycleExtensionTest {

    @Test
    void shouldAlwaysSetMarkdownProperties() {
        final Properties properties = new Properties();
        final MarkdownSetupEngineLifecycleExtension sut = new MarkdownSetupEngineLifecycleExtension();
        sut.onInit( properties );

        // Markdown is the only supported syntax — properties are always set
        Assertions.assertEquals( 5, properties.size() );
        Assertions.assertEquals( "com.wikantik.parser.markdown.MarkdownParser",
                properties.getProperty( "wikantik.renderingManager.markupParser" ) );
        Assertions.assertEquals( "com.wikantik.render.markdown.MarkdownRenderer",
                properties.getProperty( "wikantik.renderingManager.renderer" ) );
        Assertions.assertEquals( "com.wikantik.render.markdown.MarkdownRenderer",
                properties.getProperty( "wikantik.renderingManager.renderer.wysiwyg" ) );
        Assertions.assertEquals( "plain/wiki-snips-markdown.js",
                properties.getProperty( "wikantik.syntax.plain" ) );
        Assertions.assertEquals( "true",
                properties.getProperty( "wikantik.translatorReader.allowHTML" ) );
    }

    @Test
    void shouldSetPropertiesEvenWithoutSyntaxProperty() {
        // Previously the extension required wikantik.syntax=markdown.
        // Now it always sets Markdown since it's the only supported syntax.
        final Properties properties = new Properties();
        final MarkdownSetupEngineLifecycleExtension sut = new MarkdownSetupEngineLifecycleExtension();
        sut.onInit( properties );
        Assertions.assertEquals( 5, properties.size() );
    }
}

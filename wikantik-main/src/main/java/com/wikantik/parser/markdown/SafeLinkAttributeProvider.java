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
package com.wikantik.parser.markdown;

import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.AttributeProviderFactory;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.AttributablePart;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.html.Attribute;
import com.vladsch.flexmark.util.html.MutableAttributes;

import java.util.Set;

/**
 * Flexmark {@link AttributeProvider} that rewrites any {@code href} or
 * {@code src} attribute whose scheme is outside a short allowlist to
 * {@code about:blank}. Blocks {@code javascript:}, {@code vbscript:},
 * {@code data:} and similar URIs embedded in user-authored Markdown
 * links and images, independently of whether raw HTML is allowed.
 *
 * <p>Relative URIs (starting with {@code /}, {@code #}, {@code .},
 * {@code ?}) and schemeless references are left untouched.</p>
 */
public class SafeLinkAttributeProvider implements AttributeProvider {

    private static final Set< String > SAFE_SCHEMES = Set.of( "http", "https", "mailto" );
    static final String BLOCKED_REPLACEMENT = "about:blank";

    @Override
    public void setAttributes( final Node node, final AttributablePart part, final MutableAttributes attributes ) {
        scrub( attributes, "href" );
        scrub( attributes, "src" );
    }

    private static void scrub( final MutableAttributes attributes, final String name ) {
        final Attribute attr = attributes.get( name );
        if ( attr == null ) {
            return;
        }
        final CharSequence rawValue = attr.getValue();
        if ( rawValue == null ) {
            return;
        }
        final String value = rawValue.toString().trim();
        if ( value.isEmpty() ) {
            return;
        }
        if ( isRelative( value ) ) {
            return;
        }
        final int colonIdx = value.indexOf( ':' );
        if ( colonIdx <= 0 ) {
            return;
        }
        final String scheme = value.substring( 0, colonIdx ).toLowerCase().trim();
        if ( !SAFE_SCHEMES.contains( scheme ) ) {
            attributes.replaceValue( name, BLOCKED_REPLACEMENT );
        }
    }

    private static boolean isRelative( final String value ) {
        final char c = value.charAt( 0 );
        return c == '/' || c == '#' || c == '.' || c == '?';
    }

    /**
     * Factory so the provider can be registered via
     * {@code HtmlRenderer.Builder#attributeProviderFactory}.
     */
    public static class Factory extends IndependentAttributeProviderFactory {
        @Override
        public AttributeProvider apply( final LinkResolverContext context ) {
            return new SafeLinkAttributeProvider();
        }

        public static AttributeProviderFactory create() {
            return new Factory();
        }
    }
}

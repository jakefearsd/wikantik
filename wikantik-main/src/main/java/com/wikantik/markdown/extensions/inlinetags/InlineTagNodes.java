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
package com.wikantik.markdown.extensions.inlinetags;

import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.RefNode;
import com.vladsch.flexmark.ext.wikilink.WikiLink;
import com.vladsch.flexmark.util.ast.Node;

/** What the reader's page makes of a parsed {@link InlineTag}: shared by the renderer and the tag reader. */
public final class InlineTagNodes {

    private InlineTagNodes() {
    }

    /**
     * Whether the page shows {@code node} as a tag link. It does not when the tag sits in text the page shows as a link: an
     * inline link, a wikilink or an image, or a reference whose definition exists. An unresolved {@code [reference]} renders
     * as plain text, so a tag in it is a tag (the index agrees).
     *
     * @param node a parsed inline tag
     * @return {@code true} when the render draws it as a tag link
     */
    public static boolean shown( final InlineTag node ) {
        for ( Node n = node.getParent(); n != null; n = n.getParent() ) {
            if ( n instanceof Link || n instanceof WikiLink || n instanceof com.vladsch.flexmark.ast.Image ) {
                return false;
            }
            if ( n instanceof RefNode && ( ( RefNode ) n ).isDefined() ) {
                return false;
            }
        }
        return true;
    }
}

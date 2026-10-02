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
package com.wikantik.markdown.nodes;

import com.vladsch.flexmark.ast.Link;


/**
 * A link produced from the native {@code [[Page]]} syntax. Rendered exactly like a {@link WikantikLink},
 * but its {@link Kind} tells the attribute provider which attributes to apply without re-classifying the URL.
 */
public class NativeWikiLinkNode extends WikantikLink {

    /** What the link points at. */
    public enum Kind { PAGE, MISSING, ANCHOR, ATTACHMENT }

    private final Kind kind;
    private final String target;

    public NativeWikiLinkNode( final Link link, final Kind kind, final String target ) {
        super( link );
        this.kind = kind;
        this.target = target;
    }

    public Kind kind() {
        return kind;
    }

    public String target() {
        return target;
    }

}

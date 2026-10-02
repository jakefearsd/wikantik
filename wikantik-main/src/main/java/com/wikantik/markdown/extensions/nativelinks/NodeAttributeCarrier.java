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
package com.wikantik.markdown.extensions.nativelinks;

import com.vladsch.flexmark.ext.attributes.AttributesExtension;
import com.vladsch.flexmark.ext.attributes.internal.NodeAttributeRepository;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;

/**
 * The attributes extension keys a trailing {@code {.cls}} by the node it follows, in a document-level repository, so
 * swapping a node for its replacement would orphan them; {@link #carry} re-keys them onto the replacement.
 */
final class NodeAttributeCarrier {

    private NodeAttributeCarrier() {
    }

    static void carry( final Node from, final Node to ) {
        final Document document = from.getDocument();
        if ( document == null ) {
            return;
        }
        final NodeAttributeRepository repository = AttributesExtension.NODE_ATTRIBUTES.getFrom( document );
        if ( repository != null && repository.containsKey( from ) ) {
            repository.put( to, repository.remove( from ) );
        }
    }
}

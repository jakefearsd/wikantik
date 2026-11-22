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
package com.wikantik.work;

/** Renders an item body (markdown) to HTML; implemented by the host so this module needs no wiki renderer. */
public interface WorkRenderer {

    String toHtml( String markdown );

    /**
     * As {@link #toHtml(String)} for text that belongs to the item with this key, so a renderer can resolve what the
     * text says about that item (an attachment token) and nothing about any other. The default ignores the key.
     */
    default String toHtml( final String markdown, final String itemKey ) {
        return toHtml( markdown );
    }
}

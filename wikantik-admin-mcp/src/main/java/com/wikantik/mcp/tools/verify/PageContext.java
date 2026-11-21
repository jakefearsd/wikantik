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
package com.wikantik.mcp.tools.verify;

import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.ParsedPage;

import java.util.Map;
import java.util.function.Supplier;

/**
 * A page under verification: its name, its {@link Page}, and its frontmatter, which is parsed
 * lazily, at most once, and only when a check asks for it.
 */
public final class PageContext {

    private final String pageName;
    private final Page page;
    private final Supplier< ParsedPage > parser;
    private ParsedPage parsed;

    public PageContext( final String pageName, final Page page, final Supplier< ParsedPage > parser ) {
        this.pageName = pageName;
        this.page = page;
        this.parser = parser;
    }

    public String pageName() {
        return pageName;
    }

    public Page page() {
        return page;
    }

    public Map< String, Object > metadata() {
        return parsed().metadata();
    }

    public String body() {
        return parsed().body();
    }

    private ParsedPage parsed() {
        if ( parsed == null ) {
            parsed = parser.get();
        }
        return parsed;
    }
}

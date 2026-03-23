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
package com.wikantik.test;

import com.wikantik.api.core.Engine;
import com.wikantik.content.SystemPageRegistry;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Stub implementation of {@link SystemPageRegistry} for unit testing.
 * Reports no system pages by default. Use {@link #addSystemPage(String)}
 * to register pages as system pages.
 */
public class StubSystemPageRegistry implements SystemPageRegistry {

    private final Set< String > systemPages = new HashSet<>();

    /**
     * Register a page name as a system page.
     */
    public void addSystemPage( final String pageName ) {
        systemPages.add( pageName );
    }

    @Override
    public boolean isSystemPage( final String pageName ) {
        return systemPages.contains( pageName );
    }

    @Override
    public Set< String > getSystemPageNames() {
        return Set.copyOf( systemPages );
    }

    @Override
    public void initialize( final Engine engine, final Properties properties ) {
    }
}

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
package com.wikantik.knowledge.test;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal in-memory "page manager" providing just enough surface area for
 * HubDiscoveryService tests: page-exists checks, put/get/list of raw markdown text.
 * Not an implementation of {@link com.wikantik.api.managers.PageManager} — that
 * interface has too many callbacks for a focused test fake.
 */
public class InMemoryPageManager {
    private final Map< String, String > pages = new HashMap<>();

    public boolean exists( final String name ) { return pages.containsKey( name ); }
    public String getText( final String name ) { return pages.get( name ); }
    public void putText( final String name, final String text ) { pages.put( name, text ); }
    public int size() { return pages.size(); }
}

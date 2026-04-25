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
package com.wikantik.api.agent;

/**
 * One entry in the page's heading outline. {@code level} is the markdown
 * heading depth (typically 2 or 3 — the projection skips level 1, which is
 * always the page title and adds no information).
 */
public record HeadingOutline( int level, String text ) {
    public HeadingOutline {
        if ( level < 2 || level > 6 ) {
            throw new IllegalArgumentException( "level must be 2..6, was " + level );
        }
        if ( text == null || text.isBlank() ) {
            throw new IllegalArgumentException( "HeadingOutline.text required" );
        }
    }
}

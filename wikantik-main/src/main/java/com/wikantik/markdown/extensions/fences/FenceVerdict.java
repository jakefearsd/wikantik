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
package com.wikantik.markdown.extensions.fences;

/**
 * Outcome of a renderer's safety policy.
 *
 * @param allowed true when the fence may be drawn
 * @param reason  machine reason when refused (e.g. {@code callback}, {@code unsafe-link},
 *                {@code unsafe-config}); null when allowed
 */
public record FenceVerdict( boolean allowed, String reason ) {

    public static final FenceVerdict ALLOWED = new FenceVerdict( true, null );

    public static FenceVerdict refused( final String reason ) {
        return new FenceVerdict( false, reason );
    }
}

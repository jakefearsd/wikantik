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
package com.wikantik.importer;

/** Resolves link targets found in a vault note to their wiki names. */
public interface VaultTargets {

    /** Page target {@code target} (no fragment). RENAME value = wiki page name to write (written only if different). */
    LinkTarget page( String target, String fromPath, boolean relative );

    /** File target including extension. RENAME value = {@code Owner/file}. KEEP = blocked (leave as written). */
    LinkTarget attachment( String target, String fromPath, boolean relative );
}

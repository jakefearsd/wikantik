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
package com.wikantik.seo;

import java.time.Instant;

/**
 * A page's content-changed date: the time of the last substantial body edit, and the version the next edit
 * is compared against.
 *
 * @param pageName         the page name
 * @param contentChangedAt when the body last changed substantially
 * @param baselineVersion  the version holding the baseline text
 */
public record ContentDate( String pageName, Instant contentChangedAt, int baselineVersion ) {}

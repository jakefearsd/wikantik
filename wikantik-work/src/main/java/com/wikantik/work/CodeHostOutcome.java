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

/** What one code-host event did to one item's change records. */
public enum CodeHostOutcome {
    /** Something was written. */
    APPLIED,
    /** Nothing was written because the wiki already knew (a redelivery, an old head, a standing approval). */
    DUPLICATE,
    /** Content the wiki cannot or will not use (an unknown key, a closed item, another repository). */
    IGNORED;

    /** The label the metric and the REST answer use. */
    public String wire() {
        return name().toLowerCase( java.util.Locale.ROOT );
    }
}

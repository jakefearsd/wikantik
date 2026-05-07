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
package com.wikantik.render.subsystem.spam;

/**
 * Internal value type representing a page-content change for spam inspection.
 * Extracted from {@code SpamFilter} in Phase 6 Checkpoint 3 of the
 * wikantik-main subsystem decomposition.
 */
public final class SpamChange {

    public String change;
    public int    adds;
    public int    removals;

    @Override
    public String toString() {
        return change;
    }

    @Override
    public boolean equals( final Object o ) {
        return o instanceof SpamChange c && change.equals( c.change );
    }

    @Override
    public int hashCode() {
        return change.hashCode() + 17;
    }
}

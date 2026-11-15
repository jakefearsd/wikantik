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

/** Item level; hierarchy is task, story, epic. */
public enum WorkLevel {
    EPIC("epic"),
    STORY("story"),
    TASK("task");

    private final String wire;

    WorkLevel( final String wire ) {
        this.wire = wire;
    }

    /** The lowercase value as stored in the database. */
    public String wire() {
        return wire;
    }

    /** Parses a stored value; throws {@link WorkValidationException} when unknown. */
    public static WorkLevel fromWire( final String value ) {
        for ( final WorkLevel candidate : values() ) {
            if ( candidate.wire.equals( value ) ) {
                return candidate;
            }
        }
        throw new WorkValidationException( "level", "Unknown level: " + value );
    }

    /** True when an item of this level may be the parent of an item of {@code child} level. */
    public boolean canParent( final WorkLevel child ) {
        return this == EPIC && child == STORY || this == STORY && child == TASK;
    }
}

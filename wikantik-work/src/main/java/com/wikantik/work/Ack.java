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

import java.time.Instant;

/**
 * Whether an item was closed with no person in its path and who has acknowledged that since. {@code required}
 * stays true after the acknowledgement: it records how the item was closed.
 */
public record Ack( boolean required, String acknowledgedBy, Instant acknowledgedAt ) {

    public static final Ack NONE = new Ack( false, null, null );

    /** Closed without a person and not yet acknowledged by one. */
    public boolean waiting() {
        return required && acknowledgedAt == null;
    }
}

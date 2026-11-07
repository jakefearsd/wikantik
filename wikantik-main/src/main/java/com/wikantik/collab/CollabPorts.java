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
package com.wikantik.collab;

import java.time.Clock;
import java.util.Objects;

/** The ports one collaboration session runs over (section 5.2): durable state, the page store, identity, authorization, time. */
public record CollabPorts( CollabStore store, CollabPageWriter writer, CollabIdentity identity,
                           CollabPermissions permissions, Clock clock ) {
    public CollabPorts {
        Objects.requireNonNull( store, "store" );
        Objects.requireNonNull( writer, "writer" );
        Objects.requireNonNull( identity, "identity" );
        Objects.requireNonNull( permissions, "permissions" );
        Objects.requireNonNull( clock, "clock" );
    }
}

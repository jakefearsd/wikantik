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

import java.util.List;

/** A change to a webhook; null leaves a part as it is. {@code enabled} false switches it off, true switches a stopped one on again. */
public record WebhookPatch( String url, List< String > eventTypes, Boolean enabled ) {

    /** Never prints the address: its path may be a credential. */
    @Override
    public String toString() {
        return "WebhookPatch[eventTypes=" + eventTypes + ", enabled=" + enabled + "]";
    }
}

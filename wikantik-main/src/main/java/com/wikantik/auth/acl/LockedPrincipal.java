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
package com.wikantik.auth.acl;

import java.security.Principal;

/** The principal of {@link DefaultAclManager#LOCKED_PRINCIPAL}. Equal only to itself. */
final class LockedPrincipal implements Principal {
    @Override
    public String getName() {
        return "\u0000locked: unreadable access rules\u0000";
    }

    @Override
    public String toString() {
        return "LockedPrincipal";
    }
}

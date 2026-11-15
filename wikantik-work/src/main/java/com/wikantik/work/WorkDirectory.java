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

import java.util.Set;

/** The wiki's user database as the work module sees it; implemented by the host application. */
public interface WorkDirectory {

    /** The groups the login belongs to; empty when none or unknown. */
    Set< String > groupsOf( String login );

    /** Whether a user with this login exists. */
    boolean exists( String login );

    /** The public display name for the login. */
    String displayName( String login );
}

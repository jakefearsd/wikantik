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
package com.wikantik.auth.user;

/**
 * The three columns of a {@code users} row that identity resolution needs — no password hash, no attribute blob.
 *
 * @param login    {@code login_name}
 * @param fullName {@code full_name} (the display name); may be {@code null} or blank
 * @param uid      the <em>stored</em> {@code uid}; {@code null} when the column is NULL or blank. {@code findByLoginName}
 *                 invents a fresh random uid on every read of such a row, so it must never be used as an identifier
 */
public record AccountSummary( String login, String fullName, String uid ) { }

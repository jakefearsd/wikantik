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
package com.wikantik.auth;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Users {

    private static final Properties PROPS = new Properties();

    static {
        try (InputStream in = Users.class.getClassLoader().getResourceAsStream("test-users.properties")) {
            if (in != null) {
                PROPS.load(in);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load test-users.properties", e);
        }
    }

    public static final String ADMIN = "admin";
    public static final String ADMIN_PASS = PROPS.getProperty("admin.password");

    public static final String ALICE = "Alice";
    public static final String ALICE_PASS = PROPS.getProperty("alice.password");

    public static final String BOB = "Bob";
    public static final String BOB_PASS = PROPS.getProperty("bob.password");

    public static final String CHARLIE = "Charlie";
    public static final String CHARLIE_PASS = PROPS.getProperty("charlie.password");

    public static final String FRED = "Fred";
    public static final String FRED_PASS = PROPS.getProperty("fred.password");

    public static final String BIFF = "Biff";
    public static final String BIFF_PASS = PROPS.getProperty("biff.password");

    public static final String JANNE = "janne";
    public static final String JANNE_PASS = PROPS.getProperty("janne.password");
}

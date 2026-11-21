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
package com.wikantik.mcp.tools.verify;

import java.util.Map;

/**
 * One {@code verify_pages} check. The tool runs the active checks over each existing page in
 * declaration order, then asks each for its contribution to the summary block.
 */
public interface VerifyCheck {

    /** The check's name as it appears in the tool's {@code checks} argument. */
    String name();

    /** Adds this check's keys to the page's result entry and records its aggregates in {@code totals}. */
    void apply( PageContext page, Map< String, Object > entry, VerifyTotals totals );

    /** Adds this check's keys, if any, to the summary block. */
    void summarise( VerifyTotals totals, Map< String, Object > summary );
}

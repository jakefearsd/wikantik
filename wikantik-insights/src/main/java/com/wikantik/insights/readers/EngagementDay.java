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
package com.wikantik.insights.readers;

import java.time.LocalDate;

/** One {@code reader_engagement_daily} row; {@code canonicalId ''} is the site row, null doubles are "no data". */
public record EngagementDay( LocalDate day, String canonicalId, int views, int visitors, int exits,
                             Double activeMedianS, Double activeP90S, int exitsScroll75,
                             int codeCopies, int wikilinkClicks, int outboundClicks,
                             int diagramInteracts, int tocClicks, int signInClicks,
                             Double lcpP75Ms, Double inpP75Ms, Double clsP75 ) { }

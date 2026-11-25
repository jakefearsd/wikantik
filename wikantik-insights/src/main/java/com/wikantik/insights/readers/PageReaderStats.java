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

import java.util.Map;

/**
 * One page's reader facts over a window. A {@code null} rate or measure means "no data", never zero.
 *
 * @param typicalActiveS exit-weighted median of the daily median active-time values, in seconds
 * @param scroll75Rate share of exits that had scrolled past 75 percent
 * @param actionsPerView reader actions (code copies, wikilink, outbound, diagram, TOC and sign-in clicks) per view
 * @param landings landings by acquisition channel
 * @param continuationRate share of landings that went on to a second page
 * @param returningThisMonthRate share of landings by returning readers, over days 8+ of each month only
 * @param lcpP75Ms views-weighted mean of the daily p75 values (likewise the next two)
 */
public record PageReaderStats(
        String canonicalId, String slug, int views, int visitors,
        Double typicalActiveS, Double scroll75Rate, Double actionsPerView,
        Map< String, Integer > landings, Double continuationRate, Double returningThisMonthRate,
        Double lcpP75Ms, Double inpP75Ms, Double clsP75 ) {
}

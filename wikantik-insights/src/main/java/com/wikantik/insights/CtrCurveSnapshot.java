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
package com.wikantik.insights;

import java.time.LocalDate;
import java.util.Map;

/**
 * One dated {@code expected_ctr_curve} (V057) row set -- jakemon's real {@code EXPECTED_CTR}
 * table as shipped for a single {@code as_of} date, as returned by
 * {@link InsightsStore#latestCtrCurve}. {@link ExpectedCtrCurve#fromTable} turns {@code points}
 * into a queryable curve; this record is just the storage-shaped read result.
 *
 * @param asOf   the detector/shipper run date this curve was reported for
 * @param points position -&gt; CTR, keyed by whatever integer positions jakemon shipped (today:
 *               1-10)
 */
public record CtrCurveSnapshot( LocalDate asOf, Map<Integer, Double> points ) {
}

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

import java.util.Locale;

/**
 * Why an inbound code-host delivery was refused: the {@code reason} label of
 * {@code wikantik_work_codehost_rejected_total}. A fixed set, so the label's cardinality is bounded; it never names a
 * product. An unknown product, a missing or disabled hook and a wrong signature are all {@link #SIGNATURE}: the
 * metric does not tell them apart any more than the answer does.
 */
public enum HookRejection {

    /** The body was larger than {@code wikantik.work.codehost.maxBodyBytes} (declared or read): 413. */
    TOO_LARGE,
    /** No signature header, or one that is not {@code sha256=} and 64 hex digits; the body was never read: 404. */
    NO_SIGNATURE,
    /** The signature did not verify: unknown or archived product, no live hook, or a wrong signature: 404. */
    SIGNATURE,
    /** A verified delivery sent as a form instead of JSON: 415. */
    FORM_ENCODED,
    /** A verified delivery whose body is not a usable JSON document: 204, nothing applied. */
    UNPARSEABLE,
    /** A verified delivery holding an event with no usable host time: 204, nothing applied. */
    UNTIMED;

    /** The label value: the lower-case name. */
    public String wire() {
        return name().toLowerCase( Locale.ROOT );
    }
}

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

/**
 * A delivery whose signature was verified for one product's live hook. Only {@link WorkCodeHostService#authenticate}
 * can make one (the constructor is package-private), so {@code apply} cannot be called for an unverified delivery.
 */
public final class HookDelivery {

    private final long productId;
    private final String productKey;
    private final String host;
    private final String repoUrl;
    private final String defaultBranch;

    HookDelivery( final long productId, final String productKey, final String host, final String repoUrl,
                  final String defaultBranch ) {
        this.productId = productId;
        this.productKey = productKey;
        this.host = host;
        this.repoUrl = repoUrl;
        this.defaultBranch = defaultBranch;
    }

    public long productId() {
        return productId;
    }

    public String productKey() {
        return productKey;
    }

    /** {@code github} or {@code generic}, from the URL the delivery came to. */
    public String host() {
        return host;
    }

    /** The product's repository address, or null when it names none. */
    public String repoUrl() {
        return repoUrl;
    }

    /** The product's default branch (never null for a stored product). */
    public String defaultBranch() {
        return defaultBranch;
    }

    @Override
    public String toString() {
        return "HookDelivery[product=" + productKey + ", host=" + host + "]";
    }
}

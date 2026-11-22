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

/** The rules for naming the deployment a new one remediates (fix-forward). The caller has already seen the target. */
final class Remediation {

    static final String SAME = "A remediating deployment fixes an earlier deployment of the same product and environment.";
    static final String STATE = "Only a deployment that was verified, failed or rolled back can be remediated.";

    private Remediation() {
    }

    /**
     * Refuses a target that is of another product or environment (the environment is the normalised one: blank means
     * production), or has not ended in a state that can be fixed. A new deployment cannot be a target's ancestor, so
     * no cycle can form.
     */
    static void check( final Deployment target, final Product product, final String environment ) {
        if ( target.productId() != product.id() || !target.environment().equals( environment ) ) {
            throw new WorkValidationException( "remediates", SAME );
        }
        switch ( target.state() ) {
            case VERIFIED, FAILED, ROLLED_BACK -> { }
            default -> throw new WorkValidationException( "remediates", STATE );
        }
    }
}

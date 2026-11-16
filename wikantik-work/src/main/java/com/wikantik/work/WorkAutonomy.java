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
 * The one place an item's autonomy level, direct-commit permission and done-means are decided. Nothing else in the
 * module reads the policy columns to make a decision ({@code WorkAutonomySourceRuleTest}).
 *
 * <p>The level is the item's own value, else its nearest ancestor's, else the project's default; the product's
 * ceiling then caps it and never raises it. Direct commits follow the nearest value set on the item or an ancestor
 * and default to off. Done-means is an epic's setting: a story's or task's own value is ignored.</p>
 */
public final class WorkAutonomy {

    private WorkAutonomy() {
    }

    public static EffectivePolicy resolve( final PolicyChain chain ) {
        final Autonomy own = chain.item().autonomy();
        final Autonomy uncapped = own != null ? own : inherited( chain );
        final Product product = chain.productOrNull();
        final Autonomy level = product == null ? uncapped : Autonomy.strictest( uncapped, product.autonomyCeiling() );
        return new EffectivePolicy( level, allowDirect( chain ), doneMeans( chain ) );
    }

    /** The level the item would have with no value of its own, before the product's ceiling. */
    public static Autonomy inherited( final PolicyChain chain ) {
        for ( final Item ancestor : chain.ancestors() ) {
            if ( ancestor.autonomy() != null ) {
                return ancestor.autonomy();
            }
        }
        return chain.project().defaultAutonomy();
    }

    private static boolean allowDirect( final PolicyChain chain ) {
        if ( chain.item().allowDirect() != null ) {
            return chain.item().allowDirect();
        }
        for ( final Item ancestor : chain.ancestors() ) {
            if ( ancestor.allowDirect() != null ) {
                return ancestor.allowDirect();
            }
        }
        return false;
    }

    private static DoneMeans doneMeans( final PolicyChain chain ) {
        if ( chain.item().level() == WorkLevel.EPIC ) {
            return orMerged( chain.item().doneMeans() );
        }
        for ( final Item ancestor : chain.ancestors() ) {
            if ( ancestor.level() == WorkLevel.EPIC ) {
                return orMerged( ancestor.doneMeans() );
            }
        }
        return DoneMeans.MERGED;
    }

    private static DoneMeans orMerged( final DoneMeans value ) {
        return value == null ? DoneMeans.MERGED : value;
    }
}

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
package com.wikantik.knowledge.eval;

import com.wikantik.api.eval.BundleCategory;
import com.wikantik.api.eval.BundleEvalQuestion;
import com.wikantik.api.eval.BundleSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs the bundle evaluation corpus through a {@link BundleRetriever} and
 * aggregates {@link BundleMetricsCalculator} scores overall and per category.
 */
public final class BundleEvalRunner {

    /** query text → ordered bundle sections. */
    @FunctionalInterface
    public interface BundleRetriever extends Function< String, List< BundleSection > > {}

    private final BundleRetriever retriever;
    private final int precisionK;

    public BundleEvalRunner( final BundleRetriever retriever, final int precisionK ) {
        if ( precisionK <= 0 ) {
            throw new IllegalArgumentException( "precisionK must be positive" );
        }
        this.retriever = retriever;
        this.precisionK = precisionK;
    }

    public BundleEvalReport run( final List< BundleEvalQuestion > corpus ) {
        final Map< BundleCategory, List< Double > > recallByCat = new EnumMap<>( BundleCategory.class );
        final Map< BundleCategory, List< Double > > precByCat = new EnumMap<>( BundleCategory.class );
        for ( final BundleCategory c : BundleCategory.values() ) {
            recallByCat.put( c, new ArrayList<>() );
            precByCat.put( c, new ArrayList<>() );
        }
        final List< Double > allRecall = new ArrayList<>();
        final List< Double > allPrec = new ArrayList<>();

        for ( final BundleEvalQuestion q : corpus ) {
            final List< BundleSection > bundle = retriever.apply( q.query() );
            final double recall = BundleMetricsCalculator.contextRecall( q.goldSections(), bundle );
            final double prec = BundleMetricsCalculator.contextPrecisionAtK( q.goldSections(), bundle, precisionK );
            recallByCat.get( q.category() ).add( recall );
            precByCat.get( q.category() ).add( prec );
            allRecall.add( recall );
            allPrec.add( prec );
        }

        return new BundleEvalReport(
            mean( allRecall ), mean( allPrec ),
            meanByCategory( recallByCat ), meanByCategory( precByCat ),
            corpus.size() );
    }

    private static Map< BundleCategory, Double > meanByCategory(
            final Map< BundleCategory, List< Double > > src ) {
        final Map< BundleCategory, Double > out = new EnumMap<>( BundleCategory.class );
        for ( final Map.Entry< BundleCategory, List< Double > > e : src.entrySet() ) {
            out.put( e.getKey(), mean( e.getValue() ) );
        }
        return out;
    }

    private static double mean( final List< Double > xs ) {
        if ( xs == null || xs.isEmpty() ) return 0.0;
        double s = 0.0;
        for ( final double x : xs ) s += x;
        return s / xs.size();
    }
}

package com.wikantik.knowledge.testfakes;

import com.wikantik.api.search.SearchResult;
import com.wikantik.event.WikiEvent;
import com.wikantik.search.SearchManager;
import com.wikantik.search.SearchProvider;

import java.util.*;

/**
 * Minimal SearchManager fake for unit tests.
 * The only abstract method on SearchManager itself is getSearchEngine();
 * all PageFilter and WikiEventListener methods have default implementations.
 * We override findPages() directly (it has a default but calls getSearchEngine),
 * so tests can inject results without a real search provider.
 */
public class FakeSearchManager implements SearchManager {

    private List< SearchResult > nextResults = List.of();

    public void setResults( final List< SearchResult > results ) {
        this.nextResults = List.copyOf( results );
    }

    @Override
    public Collection< SearchResult > findPages( final String query,
            final com.wikantik.api.core.Context ctx ) {
        return nextResults;
    }

    @Override
    public SearchProvider getSearchEngine() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void actionPerformed( final WikiEvent event ) {
        throw new UnsupportedOperationException();
    }
}

package com.wikantik.knowledge.testfakes;

import com.wikantik.knowledge.DefaultContextRetrievalService;

/** Test-only builder that wires null-tolerant deps into the service. */
public final class FakeDeps {

    private FakeSearchManager search = new FakeSearchManager();
    private FakePageManager pageManager = new FakePageManager();
    private String baseUrl = "";

    public static FakeDeps minimal() { return new FakeDeps(); }

    public FakeDeps search( final FakeSearchManager s ) { this.search = s; return this; }
    public FakeDeps pageManager( final FakePageManager pm ) { this.pageManager = pm; return this; }
    public FakeDeps baseUrl( final String u ) { this.baseUrl = u; return this; }

    public DefaultContextRetrievalService build() {
        return new DefaultContextRetrievalService(
            search, null, null, null, null, null, pageManager, null, baseUrl );
    }
}

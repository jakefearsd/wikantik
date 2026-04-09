package com.wikantik.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HubSyncFilterTest {

    private Map< String, String > pageStore;
    private HubSyncFilter filter;

    @BeforeEach
    void setUp() {
        pageStore = new HashMap<>();
        filter = new HubSyncFilter( pageStore::get, pageStore::put );
    }

    /** Member page adds a hub → Hub's related list should include the member. */
    @Test
    void memberAddsHub_syncsHubRelatedList() {
        // TechHub exists with no related members
        pageStore.put( "TechHub", "---\ntype: hub\ntitle: TechHub\n---\nHub body." );

        final String oldContent = "---\ntitle: MyArticle\n---\nBody.";
        final String newContent = "---\ntitle: MyArticle\nhubs:\n- TechHub\n---\nBody.";

        filter.syncAfterSave( "MyArticle", newContent, oldContent );

        final String hubContent = pageStore.get( "TechHub" );
        assertNotNull( hubContent, "TechHub should still be in the store" );
        assertTrue( hubContent.contains( "MyArticle" ),
            "TechHub's content should reference MyArticle after sync" );
    }

    /** Hub adds a member to its related list → member page's hubs list should include the hub. */
    @Test
    void hubAddsMember_syncsMemberHubsList() {
        // MyArticle exists with no hubs
        pageStore.put( "MyArticle", "---\ntitle: MyArticle\n---\nArticle body." );

        final String oldContent = "---\ntype: hub\ntitle: TechHub\n---\nHub body.";
        final String newContent = "---\ntype: hub\ntitle: TechHub\nrelated:\n- MyArticle\n---\nHub body.";

        filter.syncAfterSave( "TechHub", newContent, oldContent );

        final String memberContent = pageStore.get( "MyArticle" );
        assertNotNull( memberContent, "MyArticle should still be in the store" );
        assertTrue( memberContent.contains( "TechHub" ),
            "MyArticle's content should reference TechHub after sync" );
    }

    /** Member removes a hub from its hubs list → Hub's related list should no longer include the member. */
    @Test
    void memberRemovesHub_updatesHubRelatedList() {
        // TechHub already lists MyArticle in related
        pageStore.put( "TechHub", "---\ntype: hub\ntitle: TechHub\nrelated:\n- MyArticle\n---\nHub body." );

        final String oldContent = "---\ntitle: MyArticle\nhubs:\n- TechHub\n---\nBody.";
        final String newContent = "---\ntitle: MyArticle\n---\nBody.";

        filter.syncAfterSave( "MyArticle", newContent, oldContent );

        final String hubContent = pageStore.get( "TechHub" );
        assertNotNull( hubContent );
        assertFalse( hubContent.contains( "MyArticle" ),
            "TechHub's related list should no longer contain MyArticle" );
    }

    /** Referenced target page does not exist → no exception thrown, store unchanged. */
    @Test
    void noOpWhenTargetPageDoesNotExist() {
        // Ghost hub — not in the store
        final String oldContent = "---\ntitle: MyArticle\n---\nBody.";
        final String newContent = "---\ntitle: MyArticle\nhubs:\n- GhostHub\n---\nBody.";

        assertDoesNotThrow( () -> filter.syncAfterSave( "MyArticle", newContent, oldContent ) );
        assertNull( pageStore.get( "GhostHub" ),
            "Ghost page should not have been created" );
    }

    /**
     * Verifies that secondary saves do not trigger another round of sync (no infinite recursion).
     * The SUPPRESS_SYNC flag must prevent re-entry.
     */
    @Test
    void noRecursion_secondarySaveDoesNotTriggerSync() {
        // Set up a hub and a member
        pageStore.put( "TechHub", "---\ntype: hub\ntitle: TechHub\n---\nHub body." );
        pageStore.put( "MyArticle", "---\ntitle: MyArticle\n---\nArticle body." );

        final String oldContent = "---\ntitle: MyArticle\n---\nBody.";
        final String newContent = "---\ntitle: MyArticle\nhubs:\n- TechHub\n---\nBody.";

        // Should complete without StackOverflowError
        assertDoesNotThrow( () -> filter.syncAfterSave( "MyArticle", newContent, oldContent ) );

        // Both pages should exist and be updated exactly once
        assertNotNull( pageStore.get( "TechHub" ) );
        assertNotNull( pageStore.get( "MyArticle" ) );
    }
}

package com.wikantik.connectors.webcrawler;

import com.wikantik.api.connectors.SourceItem;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class WebFetchItemsTest {
    @Test void sha256HexIs64LowercaseHex() {
        String h = WebFetchItems.sha256Hex( "abc".getBytes( StandardCharsets.UTF_8 ) );
        assertEquals( 64, h.length() );
        assertTrue( h.matches( "[0-9a-f]{64}" ) );
    }
    @Test void toItemBuildsTextHtmlSourceItem() {
        byte[] body = "<html><head><title>Hi</title></head><body>x</body></html>".getBytes( StandardCharsets.UTF_8 );
        SourceItem i = WebFetchItems.toItem( "https://ex.com/p", new FetchResult( 200, "text/html", body, "https://ex.com/p" ) );
        assertEquals( "https://ex.com/p", i.sourceUri() );
        assertEquals( "text/html", i.contentType() );
        assertTrue( i.aclRefs().isEmpty() );
        assertEquals( 64, i.contentHash().length() );
        assertEquals( "https://ex.com/p", i.sourceMetadata().get( "url" ) );
        assertEquals( "Hi", i.sourceMetadata().get( "title" ) );
        assertEquals( 200, i.sourceMetadata().get( "httpStatus" ) );
        assertNotNull( i.sourceMetadata().get( "fetchedAt" ) );
    }
}

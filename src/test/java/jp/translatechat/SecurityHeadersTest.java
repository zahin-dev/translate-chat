package jp.translatechat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersTest {
    private MockHttpServletResponse post(String publicOrigin, String browserOrigin) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/rooms");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8080);
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader("Origin", browserOrigin);
        // These client-supplied headers must not grant access to another origin.
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "untrusted.example");
        var response = new MockHttpServletResponse();
        new SecurityHeaders(publicOrigin).doFilter(request, response, (req, res) -> res.getWriter().write("accepted"));
        return response;
    }

    @Test void cloudHttpsOriginWorksBehindAnHttpProxy() throws Exception {
        var response = post("https://chat.example/", "https://chat.example");
        assertEquals(200, response.getStatus());
        assertEquals("accepted", response.getContentAsString());
        assertEquals(200, post("https://chat.example:443", "https://chat.example").getStatus());
    }

    @Test void otherOriginsStayBlockedIncludingSpoofedForwardingHeaders() throws Exception {
        var response = post("https://chat.example", "https://untrusted.example");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_ORIGIN"));
        assertEquals(403, post("https://chat.example", "http://chat.example").getStatus());
    }

    @Test void localOriginStillWorksAndRejectsOtherSites() throws Exception {
        assertEquals(200, post("", "http://127.0.0.1:8080").getStatus());
        assertEquals(403, post("", "https://untrusted.example").getStatus());
    }

    @Test void invalidPublicOriginFailsBeforeServingRequests() {
        for (String value : new String[]{"*", "https://user:secret@chat.example", "https://chat.example/path", "https://chat.example?key=value", "ftp://chat.example"}) {
            assertThrows(IllegalArgumentException.class, () -> new SecurityHeaders(value));
        }
    }
}

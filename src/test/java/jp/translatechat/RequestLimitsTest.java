package jp.translatechat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RequestLimitsTest {
    @Test void limitsOneParticipantWithoutBlockingAnother() {
        var limits = new RequestLimits();
        limits.consume("participant-a",1,60);
        var error = assertThrows(ChatService.ApiFailure.class,() -> limits.consume("participant-a",1,60));
        assertEquals("RATE_LIMIT",error.code);
        assertDoesNotThrow(() -> limits.consume("participant-b",1,60));
    }
}

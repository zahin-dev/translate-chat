package jp.translatechat;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final ChatService chat;
    private final RequestLimits limits;

    public ChatController(ChatService chat, RequestLimits limits) { this.chat = chat; this.limits = limits; }

    @PostMapping(value = "/rooms", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    ChatService.Membership create(@RequestBody ChatService.JoinRequest request, HttpServletRequest http) {
        limits.consume("entry:" + http.getRemoteAddr(), 30, 60);
        return chat.createRoom(request);
    }

    @PostMapping(value = "/rooms/{code}/join", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    ChatService.Membership join(@PathVariable String code, @RequestBody ChatService.JoinRequest request, HttpServletRequest http) {
        limits.consume("entry:" + http.getRemoteAddr(), 30, 60);
        return chat.joinRoom(code, request);
    }

    @GetMapping("/rooms/{code}/state")
    ChatService.State state(@PathVariable String code, @RequestHeader(value = "X-Participant-Token", required = false) String token,
                            @RequestParam(defaultValue = "0") long after) {
        return chat.state(code, token, after);
    }

    @GetMapping("/rooms/{code}/messages")
    ChatService.History history(@PathVariable String code, @RequestHeader(value = "X-Participant-Token", required = false) String token,
                                @RequestParam long before) {
        return chat.olderMessages(code, token, before);
    }

    @PostMapping(value = "/rooms/{code}/messages", consumes = "application/json")
    ChatService.Message send(@PathVariable String code, @RequestHeader(value = "X-Participant-Token", required = false) String token,
                             @RequestBody ChatService.SendRequest request) {
        return chat.send(code, token, request);
    }

    @PostMapping("/rooms/{code}/leave")
    ResponseEntity<Void> leave(@PathVariable String code, @RequestHeader(value = "X-Participant-Token", required = false) String token) {
        chat.leave(code, token);
        return ResponseEntity.noContent().build();
    }
}

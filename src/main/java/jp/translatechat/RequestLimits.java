package jp.translatechat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RequestLimits {
    private final Map<String, ArrayDeque<Long>> windows = new HashMap<>();

    public synchronized void consume(String key, int limit, int seconds) {
        long now = Instant.now().getEpochSecond();
        windows.entrySet().removeIf(entry -> entry.getValue().isEmpty() || entry.getValue().getLast() < now - 600);
        if (!windows.containsKey(key) && windows.size() >= 5000) {
            throw ChatService.failure(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT");
        }
        ArrayDeque<Long> times = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && times.getFirst() <= now - seconds) { times.removeFirst(); }
        if (times.size() >= limit) { throw ChatService.failure(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT"); }
        times.addLast(now);
    }
}

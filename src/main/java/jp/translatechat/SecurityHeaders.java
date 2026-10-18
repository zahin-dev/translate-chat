package jp.translatechat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SecurityHeaders extends OncePerRequestFilter {
    private final String publicOrigin;

    public SecurityHeaders(@Value("${chat.public-origin:}") String configuredOrigin) {
        if (configuredOrigin.isBlank()) { publicOrigin = ""; return; }
        URI origin = URI.create(configuredOrigin.strip());
        String scheme = origin.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || origin.getHost() == null || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                || origin.getPort() > 65535) {
            throw new IllegalArgumentException("chat.public-origin must be an HTTP(S) origin without a path or credentials");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        int port = origin.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        publicOrigin = scheme + "://" + origin.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        response.setHeader("Permissions-Policy", "translator=(self), language-detector=(self), camera=(), microphone=(), geolocation=()");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            String origin = request.getHeader("Origin");
            String expected = publicOrigin.isEmpty() ? request.getScheme() + "://" + request.getServerName()
                    + ((request.getServerPort() == 80 || request.getServerPort() == 443) ? "" : ":" + request.getServerPort()) : publicOrigin;
            if (origin != null && !origin.equals(expected)) { error(response, 403, "INVALID_ORIGIN"); return; }
            if ("POST".equals(request.getMethod())) {
                byte[] body = request.getInputStream().readNBytes(16385);
                if (body.length > 16384) { error(response, 413, "MESSAGE_TOO_LONG"); return; }
                HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(request) {
                    @Override public ServletInputStream getInputStream() {
                        ByteArrayInputStream input = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            @Override public int read() { return input.read(); }
                            @Override public boolean isFinished() { return input.available() == 0; }
                            @Override public boolean isReady() { return true; }
                            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                        };
                    }
                };
                chain.doFilter(wrapped, response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}

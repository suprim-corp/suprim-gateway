package dev.suprim.gateway.virtualkey;

import dev.suprim.gateway.config.AppConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
@Component
public class VirtualKeyAuthFilter extends OncePerRequestFilter {

    private final AppConfig appConfig;
    private final VirtualKeyService virtualKeyService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth == null || auth.isBlank()) {
            String xApiKey = request.getHeader("x-api-key");
            if (xApiKey != null && !xApiKey.isBlank()) {
                auth = "Bearer " + xApiKey;
            }
        }

        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);

            if (token.equals(appConfig.adminApiKey())) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                authentication.setDetails("admin");
                SecurityContextHolder.getContext().setAuthentication(authentication);
                chain.doFilter(request, response);
                return;
            }

            VirtualKey key = virtualKeyService.resolveByRawKey(token);
            if (key != null && key.enabled() && key.revokedAt() == null) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        key.id(), null, List.of(new SimpleGrantedAuthority("ROLE_API")));
                authentication.setDetails(key);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                chain.doFilter(request, response);
                return;
            }
        }

        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }
}

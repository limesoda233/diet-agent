package com.diet.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class BearerAuthenticationFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final boolean localAuth;

    public BearerAuthenticationFilter(AuthService auth) {
        this(auth, false);
    }

    public BearerAuthenticationFilter(AuthService auth, boolean localAuth) {
        this.auth = auth;
        this.localAuth = localAuth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/") || path.equals("/api/v1/auth/wechat/login")) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        AuthPrincipal principal;
        try {
            if (authorization != null && authorization.startsWith("Bearer ")) {
                principal = auth.authenticate(authorization.substring(7));
            } else if (localAuth) {
                String localHeader = request.getHeader("X-User-Id");
                long userId = Long.parseLong(localHeader == null ? "1" : localHeader);
                if (userId <= 0) throw new AuthException("UNAUTHENTICATED", "请先登录");
                principal = new AuthPrincipal(userId, "ADMIN", "local");
            } else {
                throw new AuthException("UNAUTHENTICATED", "请先登录");
            }
        } catch (AuthException | NumberFormatException error) {
            SecurityErrorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHENTICATED", "请先登录");
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

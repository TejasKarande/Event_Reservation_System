package com.ticketbooking.system.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import io.jsonwebtoken.JwtException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private final JwtService jwt;

    public JwtFilter(JwtService j) {
        jwt = j;
    }

    protected void doFilterInternal(HttpServletRequest q, HttpServletResponse p, FilterChain c)
            throws ServletException, IOException {
        String h = q.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer "))
            try {
                var claims = jwt.parse(h.substring(7));
                if (claims.getSubject() == null || claims.getSubject().isBlank())
                    throw new JwtException("JWT subject is required");
                Object roles = claims.get("roles");
                List<SimpleGrantedAuthority> a = new ArrayList<>();
                if (roles instanceof Collection<?> rs)
                    rs.forEach(r -> a.add(new SimpleGrantedAuthority("ROLE_" + r)));
                var auth = new UsernamePasswordAuthenticationToken(claims.getSubject(), null, a);
                SecurityContextHolder.getContext().setAuthentication(auth);
                q.setAttribute("authenticated_user_id", claims.getSubject());
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        c.doFilter(q, p);
    }
}

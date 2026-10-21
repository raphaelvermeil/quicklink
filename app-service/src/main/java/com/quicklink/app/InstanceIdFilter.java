package com.quicklink.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps every response with the instance that served it, so load balancing is
 * observable once several copies of this app are running. Docker sets HOSTNAME
 * to the container id; outside a container this reads "local".
 */
@Component
public class InstanceIdFilter extends OncePerRequestFilter {

    private final String instanceId = System.getenv().getOrDefault("HOSTNAME", "local");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.addHeader("X-Instance-Id", instanceId);
        chain.doFilter(request, response);
    }
}

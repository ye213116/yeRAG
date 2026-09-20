package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.DefaultCorsProcessor;
import static org.junit.jupiter.api.Assertions.*;

class SecurityCorsTest {
    @Test void preflightAllowsConfiguredFrontendButRejectsForeignOrigin() throws Exception {
        var source=new SecurityConfig().corsConfigurationSource("http://localhost:*,http://127.0.0.1:*");
        for(String origin: java.util.List.of("http://localhost:9527","https://foreign.example")) {
            var request=new MockHttpServletRequest("OPTIONS","/api/v1/users/login");
            request.addHeader("Origin",origin);
            request.addHeader("Access-Control-Request-Method","POST");
            request.addHeader("Access-Control-Request-Headers","content-type,authorization,x-request-id");
            var response=new MockHttpServletResponse();
            boolean accepted=new DefaultCorsProcessor().processRequest(source.getCorsConfiguration(request),request,response);
            assertEquals(origin.contains("localhost"),accepted);
            if(accepted) assertEquals(origin,response.getHeader("Access-Control-Allow-Origin"));
            else assertEquals(403,response.getStatus());
        }
    }
}

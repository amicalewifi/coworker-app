package ch.amicalewifi.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication auth) throws IOException {
        boolean isCoworker = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_COWORKER"));
        response.sendRedirect(request.getContextPath() + (isCoworker ? "/mobile/" : "/admin/"));
    }
}

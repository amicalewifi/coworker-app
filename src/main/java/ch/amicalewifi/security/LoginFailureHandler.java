package ch.amicalewifi.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Customise login failure redirects so the /login page can distinguish:
 *   - Compte désactivé          → /login?disabled
 *   - Mauvais email/password   → /login?error      (default behaviour)
 *
 * (Unverified-email accounts can now log in — they are nagged via a
 * banner inside the app rather than blocked at the auth step.)
 */
@Component
@RequiredArgsConstructor
public class LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception)
            throws IOException, ServletException {
        String target = (exception instanceof DisabledException)
                ? "/login?disabled"
                : "/login?error";
        setDefaultFailureUrl(target);
        super.onAuthenticationFailure(request, response, exception);
    }
}

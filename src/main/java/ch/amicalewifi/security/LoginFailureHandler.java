package ch.amicalewifi.security;

import ch.amicalewifi.repository.UserRepository;
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
 *   - Email not yet vérifié    → /login?unverified  (banner + resend form)
 *   - Compte désactivé          → /login?disabled
 *   - Mauvais email/password   → /login?error      (current behaviour)
 *
 * Why a custom handler: UserDetailsService marks unverified-email accounts as
 * `disabled`, which throws DisabledException at auth time. The default Spring
 * Security flow lumps that under the generic ?error. We disambiguate by
 * looking the user up post-failure to figure out which kind of "disabled"
 * caused this.
 */
@Component
@RequiredArgsConstructor
public class LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final UserRepository userRepo;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception)
            throws IOException, ServletException {
        String target = "/login?error";
        if (exception instanceof DisabledException) {
            String email = request.getParameter("username");
            boolean unverified = email != null
                    && userRepo.findByEmail(email)
                        .map(u -> !u.isEmailVerified())
                        .orElse(false);
            target = unverified ? "/login?unverified" : "/login?disabled";
        }
        setDefaultFailureUrl(target);
        super.onAuthenticationFailure(request, response, exception);
    }
}

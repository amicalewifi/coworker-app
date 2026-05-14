package ch.amicalewifi.security;

import ch.amicalewifi.model.User;
import ch.amicalewifi.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LoginFailureHandlerTest {

    private final UserRepository userRepo = mock(UserRepository.class);
    private final LoginFailureHandler handler = new LoginFailureHandler(userRepo);
    private final HttpServletRequest req = mock(HttpServletRequest.class);
    private final HttpServletResponse resp = mock(HttpServletResponse.class);

    LoginFailureHandlerTest() {
        // DefaultRedirectStrategy passes the URL through encodeRedirectURL before
        // calling sendRedirect; stub it as identity so verify(...) sees the raw URL.
        when(resp.encodeRedirectURL(anyString())).thenAnswer(inv -> inv.getArgument(0));
        // Spring Security's saveException() calls request.getSession() to stash the
        // exception. Provide a real-enough HttpSession so it doesn't NPE.
        when(req.getSession()).thenReturn(mock(HttpSession.class));
    }

    @Test
    void wrongPassword_redirectsToErrorParam() throws Exception {
        when(req.getParameter("username")).thenReturn("someone@example.com");

        handler.onAuthenticationFailure(req, resp, new BadCredentialsException("bad"));

        verify(resp).sendRedirect("/login?error");
    }

    @Test
    void disabledBecauseUnverified_redirectsToUnverifiedParam() throws Exception {
        when(req.getParameter("username")).thenReturn("new@example.com");
        when(userRepo.findByEmail("new@example.com")).thenReturn(Optional.of(
                User.builder().email("new@example.com").active(true).emailVerified(false).build()));

        handler.onAuthenticationFailure(req, resp, new DisabledException("unverified"));

        verify(resp).sendRedirect("/login?unverified");
    }

    @Test
    void disabledBecauseInactive_redirectsToDisabledParam() throws Exception {
        when(req.getParameter("username")).thenReturn("revoked@example.com");
        when(userRepo.findByEmail("revoked@example.com")).thenReturn(Optional.of(
                User.builder().email("revoked@example.com").active(false).emailVerified(true).build()));

        handler.onAuthenticationFailure(req, resp, new DisabledException("inactive"));

        verify(resp).sendRedirect("/login?disabled");
    }

    @Test
    void disabledForUnknownEmail_redirectsToDisabled() throws Exception {
        when(req.getParameter("username")).thenReturn("ghost@example.com");
        when(userRepo.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        handler.onAuthenticationFailure(req, resp, new DisabledException("ghost"));

        verify(resp).sendRedirect("/login?disabled");
    }
}

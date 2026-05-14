package ch.amicalewifi.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LoginFailureHandlerTest {

    private final LoginFailureHandler handler = new LoginFailureHandler();
    private final HttpServletRequest req = mock(HttpServletRequest.class);
    private final HttpServletResponse resp = mock(HttpServletResponse.class);

    LoginFailureHandlerTest() {
        when(req.getContextPath()).thenReturn("");
        when(resp.encodeRedirectURL(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(req.getSession()).thenReturn(mock(HttpSession.class));
    }

    @Test
    void wrongPassword_redirectsToErrorParam() throws Exception {
        handler.onAuthenticationFailure(req, resp, new BadCredentialsException("bad"));
        verify(resp).sendRedirect("/login?error");
    }

    @Test
    void disabledAccount_redirectsToDisabledParam() throws Exception {
        handler.onAuthenticationFailure(req, resp, new DisabledException("inactive"));
        verify(resp).sendRedirect("/login?disabled");
    }
}

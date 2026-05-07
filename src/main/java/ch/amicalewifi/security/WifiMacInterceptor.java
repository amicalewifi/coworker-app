package ch.amicalewifi.security;

import ch.amicalewifi.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class WifiMacInterceptor implements HandlerInterceptor {

    private final MemberRepository memberRepo;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (Boolean.TRUE.equals(request.getSession().getAttribute("skipWifiMac"))) return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;

        boolean isCoworker = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_COWORKER"));
        if (!isCoworker) return true;

        var member = memberRepo.findByEmail(auth.getName()).orElse(null);
        if (member == null || member.getWifiMac() != null) return true;

        response.sendRedirect(request.getContextPath() + "/mobile/register-device");
        return false;
    }
}

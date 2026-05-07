package ch.amicalewifi.security;

import ch.amicalewifi.repository.MemberRepository;
import ch.amicalewifi.service.UnifiService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final MemberRepository memberRepo;
    private final UnifiService     unifiService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication auth) throws IOException {
        boolean isCoworker = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_COWORKER"));

        if (isCoworker) {
            memberRepo.findByEmail(auth.getName()).ifPresent(member -> {
                if (member.getWifiMac() == null) {
                    String ip  = getClientIp(request);
                    String mac = unifiService.getMacForIp(ip);
                    if (mac != null) {
                        // MAC détectée → proposer via session, pas encore sauvegarder
                        request.getSession().setAttribute("detectedMac", mac);
                        log.info("Login WiFi MAC détectée: {} → {}", member.getDisplayName(), mac);
                    }
                }
            });
            response.sendRedirect(request.getContextPath() + "/mobile/");
        } else {
            response.sendRedirect(request.getContextPath() + "/admin/");
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}

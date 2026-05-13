package ch.amicalewifi.security;

import ch.amicalewifi.model.Member;
import ch.amicalewifi.repository.MemberRepository;
import ch.amicalewifi.service.WifiAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

@Component
@RequiredArgsConstructor
@Slf4j
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final MemberRepository  memberRepo;
    private final WifiAccessService wifiAccess;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication auth) throws IOException {
        HttpSession session = request.getSession(false);
        CaptivePortalParamFilter.CaptivePortalContext ctx = (session == null) ? null
                : (CaptivePortalParamFilter.CaptivePortalContext)
                    session.getAttribute(CaptivePortalParamFilter.SESSION_KEY);

        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);

        if (member != null && ctx != null && ctx.mac() != null) {
            wifiAccess.bindMacToMember(ctx.mac(), member);
            wifiAccess.tryAuthorize(member, ctx.mac());
        }

        if (session != null) session.removeAttribute(CaptivePortalParamFilter.SESSION_KEY);

        String target = safeOriginalUrl(ctx);
        if (target == null) {
            target = request.getContextPath() + "/mobile/";
        }
        response.sendRedirect(target);
    }

    /**
     * URL de redirection post-login fournie par UniFi (paramètre "url" du
     * portail captif). On l'accepte uniquement si c'est une URL http(s)
     * absolue — sinon on retombe sur le dashboard par défaut.
     */
    private static String safeOriginalUrl(CaptivePortalParamFilter.CaptivePortalContext ctx) {
        if (ctx == null || ctx.originalUrl() == null || ctx.originalUrl().isBlank()) return null;
        try {
            URI u = URI.create(ctx.originalUrl());
            if (u.getScheme() == null || u.getHost() == null) return null;
            String scheme = u.getScheme().toLowerCase();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            return u.toString();
        } catch (Exception e) {
            log.warn("URL de redirection captive invalide: {}",
                    CaptivePortalParamFilter.stripQuery(ctx.originalUrl()));
            return null;
        }
    }
}

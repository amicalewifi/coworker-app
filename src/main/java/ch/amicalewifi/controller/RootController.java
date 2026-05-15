package ch.amicalewifi.controller;

import ch.amicalewifi.model.Member;
import ch.amicalewifi.repository.MemberRepository;
import ch.amicalewifi.security.CaptivePortalParamFilter;
import ch.amicalewifi.service.WifiAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class RootController {

    private final MemberRepository    memberRepo;
    private final WifiAccessService   wifiAccess;

    @GetMapping("/login")
    public String login(Authentication auth, HttpServletRequest request) {
        boolean isAuthenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal());
        if (isAuthenticated) {
            // Cas du flow "Ajouter cet appareil": l'utilisateur est déjà
            // authentifié et arrive sur /login après le rebond captif UniFi
            // (CaptivePortalParamFilter a posé la nouvelle MAC en session).
            // On lie la MAC au membre et on tente une autorisation immédiate
            // — puis on renvoie sur /mobile/devices pour qu'il voie son
            // appareil dans la liste.
            HttpSession session = request.getSession(false);
            CaptivePortalParamFilter.CaptivePortalContext ctx = (session == null) ? null
                    : (CaptivePortalParamFilter.CaptivePortalContext)
                        session.getAttribute(CaptivePortalParamFilter.SESSION_KEY);
            if (ctx != null && ctx.mac() != null) {
                Member member = memberRepo.findByEmail(auth.getName()).orElse(null);
                if (member != null) {
                    wifiAccess.bindMacToMember(ctx.mac(), member);
                    wifiAccess.tryAuthorize(member, ctx.mac());
                }
                session.removeAttribute(CaptivePortalParamFilter.SESSION_KEY);
                return "redirect:/mobile/devices";
            }
            boolean isAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            return isAdmin ? "redirect:/admin/" : "redirect:/mobile/";
        }
        return "auth/login";
    }

    @GetMapping("/")
    public String root(Authentication auth) {
        if (auth == null) return "redirect:/login";
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return isAdmin ? "redirect:/admin/" : "redirect:/mobile/";
    }
}

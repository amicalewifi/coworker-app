package ch.amicalewifi.controller;

import ch.amicalewifi.security.CaptivePortalParamFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RootController {

    /**
     * URL HTTP vers laquelle on redirige depuis /login quand on n'a pas la
     * MAC du client (entrée directe sur coworker.amicalewifi.ch). UniFi
     * intercepte cette requête HTTP et 302 vers /guest/s/default/?id=<MAC>&…
     * qui capture la MAC via CaptivePortalParamFilter. URL en HTTP délibérément
     * — UniFi n'intercepte pas le HTTPS vers les hôtes hors walled-garden.
     *
     * Configurée via amicale.wifi.trampoline-url ; valeur par défaut pointe
     * vers l'IP côté tunnel WireGuard du VPS, où Caddy sert un filet de
     * sécurité (302 vers /login?tried=1) si UniFi rate l'intercept.
     */
    @Value("${amicale.wifi.trampoline-url}")
    private String trampolineUrl;

    @GetMapping("/login")
    public String login(@RequestParam(value = "tried", required = false) String tried,
                        Authentication auth,
                        HttpServletRequest request,
                        Model model) {
        // Utilisateur déjà authentifié : pas de dance captive nécessaire.
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            boolean isAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            return isAdmin ? "redirect:/admin/" : "redirect:/mobile/";
        }
        HttpSession session = request.getSession(false);
        boolean hasCaptiveCtx = session != null
                && session.getAttribute(CaptivePortalParamFilter.SESSION_KEY) != null;
        // Si UniFi a fourni la MAC (CaptivePortalParamFilter l'a posée en
        // session), on rend le formulaire. Sinon, on rebondit via le trampoline
        // pour forcer l'intercept UniFi. Le paramètre ?tried=1 est positionné
        // par le filet de sécurité Caddy quand UniFi rate l'intercept : on
        // affiche alors une page d'erreur explicite, pas le formulaire.
        if (hasCaptiveCtx) {
            return "auth/login";
        }
        if ("1".equals(tried)) {
            model.addAttribute("noMacWarning", true);
            return "auth/login";
        }
        return "redirect:" + trampolineUrl;
    }

    @GetMapping("/")
    public String root(Authentication auth) {
        if (auth == null) return "redirect:/login";
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return isAdmin ? "redirect:/admin/" : "redirect:/mobile/";
    }
}

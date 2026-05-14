package ch.amicalewifi.controller;

import ch.amicalewifi.security.CaptivePortalParamFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class RootController {

    /**
     * Hôte délibérément absent du walled-garden UniFi. Quand un client non
     * authentifié arrive sur /login sans MAC dans la query (entrée directe
     * sur coworker.amicalewifi.ch, qui est en walled-garden et donc jamais
     * intercepté par UniFi), on le renvoie ici : UniFi intercepte alors la
     * requête et 302 vers /login?id=<MAC>&... — on capture la MAC via le
     * même mécanisme que pour les utilisateurs déjà au portail captif.
     *
     * URL en HTTP volontairement : UniFi n'intercepte pas le HTTPS vers les
     * hôtes hors walled-garden (il bloquerait au niveau TCP). Le 302 HTTP est
     * le seul mécanisme d'interception fiable côté UniFi.
     */
    private static final String WIFI_AUTH_TRAMPOLINE = "http://wifi-auth.amicalewifi.ch/";

    @GetMapping("/login")
    public String login(@RequestParam(value = "nomac", required = false) String nomac,
                        Authentication auth,
                        HttpServletRequest request,
                        Model model) {
        // Utilisateur déjà authentifié : la dance wifi-auth n'a pas de sens.
        // On le renvoie vers son dashboard (admin ou mobile).
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            boolean isAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            return isAdmin ? "redirect:/admin/" : "redirect:/mobile/";
        }
        // Si UniFi nous a déjà fourni la MAC (CaptivePortalParamFilter l'a posée
        // en session), ou si le filet de sécurité Caddy nous a renvoyés avec
        // nomac=1, on rend le formulaire normalement. Sinon, on fait rebondir
        // le client par wifi-auth.amicalewifi.ch pour forcer l'intercept UniFi.
        HttpSession session = request.getSession(false);
        boolean hasCaptiveCtx = session != null
                && session.getAttribute(CaptivePortalParamFilter.SESSION_KEY) != null;
        if (!hasCaptiveCtx && !"1".equals(nomac)) {
            return "redirect:" + WIFI_AUTH_TRAMPOLINE;
        }
        if ("1".equals(nomac)) {
            model.addAttribute("noMacWarning", true);
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

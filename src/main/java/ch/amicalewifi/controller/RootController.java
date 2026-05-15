package ch.amicalewifi.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootController {

    @GetMapping("/login")
    public String login(Authentication auth) {
        // Utilisateurs déjà authentifiés: vers leur dashboard. Sinon, on rend
        // le formulaire — accessible depuis n'importe quel réseau (LAN coworking,
        // cellulaire, WiFi maison) pour la gestion de compte. L'autorisation
        // WiFi (capture de MAC, authorize-guest) se fait via /mobile/devices,
        // pas au login.
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
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

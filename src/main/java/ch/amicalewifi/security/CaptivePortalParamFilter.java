package ch.amicalewifi.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Capture les paramètres du portail captif UniFi à l'arrivée sur /login.
 *
 * UniFi redirige les clients non-authentifiés vers
 *   https://coworker.amicalewifi.ch/login?id=<MAC>&ap=<AP>&ssid=<SSID>&t=<TS>&url=<orig>
 *
 * On stocke ces paramètres en session pour qu'ils survivent au POST du
 * formulaire de login, puis LoginSuccessHandler les consomme pour appeler
 * UnifiService.authorizeGuest(...) et rediriger vers l'URL d'origine.
 *
 * Idempotent : si /login est rappelé sans ces paramètres (refresh, etc.),
 * la session conserve les valeurs précédentes.
 *
 * Diagnostic : chaque GET /login est journalisé en INFO avec l'intégralité
 * des query params reçus + headers utiles, pour pouvoir reconstituer ce
 * qu'UniFi a réellement envoyé (ou ne pas envoyé) côté serveur.
 */
@Component
@Slf4j
public class CaptivePortalParamFilter implements Filter {

    public static final String SESSION_KEY = "captivePortal";

    private static final List<String> LOGGED_HEADERS = List.of(
            "X-Forwarded-For", "X-Real-IP", "X-Forwarded-Proto", "X-Forwarded-Host",
            "Host", "Referer", "User-Agent");

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) req;
        if ("GET".equals(http.getMethod()) && "/login".equals(http.getRequestURI())) {
            logLoginRequest(http);

            String mac = http.getParameter("id");
            if (mac == null) mac = http.getParameter("mac"); // tolère l'ancien nom UniFi
            if (mac != null && !mac.isBlank()) {
                CaptivePortalContext ctx = new CaptivePortalContext(
                        mac.trim(),
                        http.getParameter("ap"),
                        http.getParameter("ssid"),
                        http.getParameter("t"),
                        http.getParameter("url"));
                http.getSession(true).setAttribute(SESSION_KEY, ctx);
                log.info("Captive portal: MAC liée à la session — mac={} ssid={} ap={} url={}",
                        ctx.mac(), ctx.ssid(), ctx.ap(), ctx.originalUrl());
            }
        }
        chain.doFilter(req, res);
    }

    /** Journalise la requête /login en clair pour pouvoir diagnostiquer le flux UniFi. */
    private void logLoginRequest(HttpServletRequest http) {
        String params = formatParams(http.getParameterMap());
        String headers = LOGGED_HEADERS.stream()
                .map(h -> h + "=" + Objects.toString(http.getHeader(h), ""))
                .filter(s -> !s.endsWith("="))
                .collect(Collectors.joining(", "));
        log.info("GET /login from remote={} params=[{}] headers=[{}]",
                http.getRemoteAddr(), params, headers);
    }

    private static String formatParams(Map<String, String[]> map) {
        if (map == null || map.isEmpty()) return "";
        return map.entrySet().stream()
                .map(e -> e.getKey() + "=" + String.join(",", e.getValue()))
                .collect(Collectors.joining(", "));
    }

    public record CaptivePortalContext(String mac, String ap, String ssid,
                                       String timestamp, String originalUrl)
            implements Serializable {}
}

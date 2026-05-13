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
 * Capture les paramètres du portail captif UniFi à l'arrivée sur le serveur,
 * peu importe la route exacte choisie par UniFi.
 *
 * UniFi redirige les clients non-authentifiés vers une URL qui dépend de la
 * version du contrôleur — typiquement :
 *   https://coworker.amicalewifi.ch/guest/s/<site>/?id=<MAC>&ap=<AP>&ssid=<SSID>&t=<TS>&url=<orig>
 * mais aussi parfois directement :
 *   https://coworker.amicalewifi.ch/login?id=<MAC>&...
 *
 * On capture les params dès qu'on en voit (id ou mac dans la query string,
 * sur n'importe quel GET), on les stocke en session, et on renvoie l'utilisateur
 * sur /login. Spring Security affiche le formulaire ; au login réussi,
 * LoginSuccessHandler lit la session, appelle UnifiService.authorizeGuest(...)
 * et redirige vers l'URL d'origine.
 *
 * Diagnostic : tout GET sur /login ou /guest/* est journalisé en INFO,
 * ainsi que tout GET portant id/mac quelle que soit la route, pour pouvoir
 * reconstituer ce qu'UniFi a réellement envoyé.
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
        HttpServletResponse out = (HttpServletResponse) res;
        String path = http.getRequestURI();
        boolean isGet = "GET".equals(http.getMethod());

        String mac = isGet ? extractMac(http) : null;
        boolean hasPortalParams = mac != null && !mac.isBlank();
        boolean isLoginPath  = "/login".equals(path);
        boolean isGuestPath  = path != null && path.startsWith("/guest/");

        if (isGet && (isLoginPath || isGuestPath || hasPortalParams)) {
            logRequest(http);
        }

        if (hasPortalParams) {
            CaptivePortalContext ctx = new CaptivePortalContext(
                    mac.trim(),
                    http.getParameter("ap"),
                    firstNonBlank(http.getParameter("ssid"), http.getParameter("ssidName")),
                    http.getParameter("t"),
                    http.getParameter("url"));
            http.getSession(true).setAttribute(SESSION_KEY, ctx);
            log.info("Captive portal: MAC liée à la session — path={} mac={} ssid={} ap={} url={}",
                    path, ctx.mac(), ctx.ssid(), ctx.ap(), ctx.originalUrl());

            // Si UniFi a envoyé sur autre chose que /login (typiquement
            // /guest/s/<site>/), on redirige sur /login pour montrer le formulaire.
            if (!isLoginPath) {
                out.sendRedirect("/login");
                return;
            }
        }

        chain.doFilter(req, res);
    }

    private static String extractMac(HttpServletRequest http) {
        String mac = http.getParameter("id");
        if (mac == null) mac = http.getParameter("mac"); // tolère l'ancien nom UniFi
        return mac;
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) if (s != null && !s.isBlank()) return s;
        return null;
    }

    /** Journalise la requête en clair pour diagnostiquer le flux UniFi. */
    private void logRequest(HttpServletRequest http) {
        String params = formatParams(http.getParameterMap());
        String headers = LOGGED_HEADERS.stream()
                .map(h -> h + "=" + Objects.toString(http.getHeader(h), ""))
                .filter(s -> !s.endsWith("="))
                .collect(Collectors.joining(", "));
        log.info("GET {} from remote={} params=[{}] headers=[{}]",
                http.getRequestURI(), http.getRemoteAddr(), params, headers);
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

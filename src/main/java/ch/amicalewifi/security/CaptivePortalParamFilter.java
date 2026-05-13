package ch.amicalewifi.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
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
 * reconstituer ce qu'UniFi a réellement envoyé. Les POST (form-login,
 * mot de passe) ne sont jamais journalisés.
 *
 * Redaction : les query strings de tout paramètre nommé "url" et du header
 * Referer sont supprimées avant log (l'URL d'origine peut contenir des
 * tokens — reset password, OAuth, etc.). Les paramètres dont le nom évoque
 * un secret (password, token, secret, auth, …) sont remplacés par "[REDACTED]".
 */
@Component
@Slf4j
public class CaptivePortalParamFilter implements Filter {

    public static final String SESSION_KEY = "captivePortal";

    private static final List<String> LOGGED_HEADERS = List.of(
            "X-Forwarded-For", "X-Real-IP", "X-Forwarded-Proto", "X-Forwarded-Host",
            "Host", "Referer", "User-Agent");

    /** Paramètres / headers à ne jamais logger en clair. */
    private static final Pattern SECRET_PARAM = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|api[_-]?key|authorization|auth).*");

    /** Paramètres dont la valeur est une URL : on garde scheme+host+path, on jette la query. */
    private static final Set<String> URL_PARAMS = Set.of("url", "referer", "redirect", "next");

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

        // Hard guard: only ever log GET. POST /login carries credentials.
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
                    path, ctx.mac(), ctx.ssid(), ctx.ap(), stripQuery(ctx.originalUrl()));

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

    /** Journalise la requête (sans données sensibles) pour diagnostiquer le flux UniFi. */
    private void logRequest(HttpServletRequest http) {
        String params = formatParams(http.getParameterMap());
        String headers = LOGGED_HEADERS.stream()
                .map(h -> {
                    String v = http.getHeader(h);
                    if (v == null || v.isBlank()) return null;
                    if ("Referer".equalsIgnoreCase(h)) v = stripQuery(v);
                    return h + "=" + v;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        log.info("GET {} from remote={} params=[{}] headers=[{}]",
                http.getRequestURI(), http.getRemoteAddr(), params, headers);
    }

    private static String formatParams(Map<String, String[]> map) {
        if (map == null || map.isEmpty()) return "";
        return map.entrySet().stream()
                .map(e -> e.getKey() + "=" + redactValue(e.getKey(), String.join(",", e.getValue())))
                .collect(Collectors.joining(", "));
    }

    /** Masque la valeur si le nom évoque un secret ; sinon strip la query string pour les paramètres URL. */
    static String redactValue(String name, String value) {
        if (value == null) return "";
        if (SECRET_PARAM.matcher(name).matches()) return "[REDACTED]";
        if (URL_PARAMS.contains(name.toLowerCase())) return stripQuery(value);
        return value;
    }

    /** Garde scheme+host+path d'une URL, jette query+fragment (qui peuvent contenir des tokens). */
    static String stripQuery(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try {
            URI u = URI.create(raw);
            if (u.getScheme() == null && u.getHost() == null) return raw; // pas une URL absolue
            StringBuilder sb = new StringBuilder();
            if (u.getScheme() != null) sb.append(u.getScheme()).append("://");
            if (u.getHost() != null)   sb.append(u.getHost());
            if (u.getPort() > 0)       sb.append(':').append(u.getPort());
            if (u.getRawPath() != null) sb.append(u.getRawPath());
            if (u.getRawQuery() != null || u.getRawFragment() != null) sb.append("?[REDACTED]");
            return sb.toString();
        } catch (Exception e) {
            return "[invalid-url]";
        }
    }

    public record CaptivePortalContext(String mac, String ap, String ssid,
                                       String timestamp, String originalUrl)
            implements Serializable {}
}

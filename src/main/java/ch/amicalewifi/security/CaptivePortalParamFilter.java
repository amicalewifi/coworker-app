package ch.amicalewifi.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;

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
 */
@Component
@Slf4j
public class CaptivePortalParamFilter implements Filter {

    public static final String SESSION_KEY = "captivePortal";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) req;
        if ("GET".equals(http.getMethod()) && "/login".equals(http.getRequestURI())) {
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
                log.info("Captive portal: MAC {} (SSID {}, AP {})",
                        ctx.mac(), ctx.ssid(), ctx.ap());
            }
        }
        chain.doFilter(req, res);
    }

    public record CaptivePortalContext(String mac, String ap, String ssid,
                                       String timestamp, String originalUrl)
            implements Serializable {}
}

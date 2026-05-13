package ch.amicalewifi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Intégration UniFi pour le portail captif externe.
 *
 * Le contrôleur UniFi redirige toute association WiFi vers
 * https://coworker.amicalewifi.ch/login?id=<MAC>&ap=<AP>&ssid=&t=&url=
 * On vérifie l'accès du membre (pack actif / contrat permanent), puis on
 * autorise (ou non) sa MAC via cette classe.
 *
 * Endpoints utilisés :
 *   POST {baseUrl}/api/s/{site}/cmd/stamgr
 *       body : {"cmd":"authorize-guest","mac":"<mac>","minutes":<n>}
 *       body : {"cmd":"unauthorize-guest","mac":"<mac>"}
 *   GET  {baseUrl}/api/s/{site}/stat/sta
 *       → liste des clients connectés (avec mac + uptime) pour le poller.
 * Authentification : header X-API-KEY.
 */
@Service
@Slf4j
public class UnifiService {

    @Value("${amicale.unifi.api-key}")               private String apiKey;
    @Value("${amicale.unifi.site:default}")          private String site;
    @Value("${amicale.unifi.base-url:https://unifi.ui.com/proxy/network}") private String baseUrl;

    private final RestTemplate rest = buildRestTemplate();

    /** RestTemplate qui accepte les certificats SSL auto-signés (contrôleur local UniFi). */
    private static RestTemplate buildRestTemplate() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            // fallback sans SSL custom
        }
        return new RestTemplate(new SimpleClientHttpRequestFactory());
    }

    /**
     * Normalise une adresse MAC vers le format lowercase aa:bb:cc:dd:ee:ff.
     * Accepte les variantes courantes : aabbccddeeff, AA-BB-CC-DD-EE-FF,
     * AA:BB:CC:DD:EE:FF, séparateurs '.', etc.
     * Retourne null si l'entrée n'est pas une MAC valide.
     */
    public static String normalizeMac(String raw) {
        if (raw == null) return null;
        String hex = raw.toLowerCase().replaceAll("[^0-9a-f]", "");
        if (hex.length() != 12) return null;
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }

    /** Statistiques globales du site UniFi (utilisé par /admin/wifi). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSiteStats() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", apiKey);
            ResponseEntity<String> resp = rest.exchange(
                    "https://api.ui.com/ea/sites", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return Map.of();
            Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(resp.getBody(), Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
            if (data == null || data.isEmpty()) return Map.of();
            Map<String, Object> siteData = data.get(0);
            Map<String, Object> stats    = (Map<String, Object>) siteData.get("statistics");
            Map<String, Object> counts   = stats != null ? (Map<String, Object>) stats.get("counts") : Map.of();
            Map<String, Object> meta     = (Map<String, Object>) siteData.get("meta");
            return Map.of(
                "siteName",       meta != null ? meta.getOrDefault("desc", "Default") : "Default",
                "wifiClient",     counts.getOrDefault("wifiClient",     0),
                "guestClient",    counts.getOrDefault("guestClient",    0),
                "totalDevice",    counts.getOrDefault("totalDevice",    0),
                "wifiDevice",     counts.getOrDefault("wifiDevice",     0),
                "offlineDevice",  counts.getOrDefault("offlineDevice",  0)
            );
        } catch (Exception e) {
            log.warn("UniFi getSiteStats: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Liste brute des clients WiFi connectés (champ par champ tel que renvoyé
     * par l'API UniFi). Chaque entrée contient au minimum :
     *   - "mac"      : adresse MAC du client
     *   - "uptime"   : secondes depuis l'association courante
     *   - "hostname" : nom d'hôte (optionnel)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getConnectedClients() {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-API-KEY", apiKey);
            String url = baseUrl + "/api/s/" + site + "/stat/sta";
            log.debug("UniFi GET {}", url);
            ResponseEntity<String> resp = rest.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) return List.of();
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(resp.getBody(), Map.class);
            List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");
            return data != null ? data : List.of();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("UniFi clients HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return List.of();
        } catch (Exception e) {
            log.warn("UniFi clients non disponibles: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Autorise une MAC sur le réseau invité UniFi pour la durée donnée.
     * Appelé après login réussi sur le portail captif quand le membre a
     * un pack actif (ou un contrat permanent).
     *
     * @param macRaw adresse MAC (n'importe quelle forme acceptée par normalizeMac)
     * @param minutes durée d'autorisation en minutes
     * @return true si l'appel UniFi a réussi
     */
    public boolean authorizeGuest(String macRaw, long minutes) {
        String mac = normalizeMac(macRaw);
        if (mac == null) {
            log.warn("UniFi authorize-guest: MAC invalide {}", macRaw);
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cmd",     "authorize-guest");
        body.put("mac",     mac);
        body.put("minutes", minutes);
        return postStamgr(body, "authorize-guest " + mac + " (" + minutes + " min)");
    }

    /**
     * Révoque l'autorisation invité pour une MAC. Le client sera renvoyé
     * sur le portail captif à sa prochaine association.
     */
    public boolean unauthorizeGuest(String macRaw) {
        String mac = normalizeMac(macRaw);
        if (mac == null) {
            log.warn("UniFi unauthorize-guest: MAC invalide {}", macRaw);
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cmd", "unauthorize-guest");
        body.put("mac", mac);
        return postStamgr(body, "unauthorize-guest " + mac);
    }

    private boolean postStamgr(Map<String, Object> body, String label) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY", apiKey);
            String url = baseUrl + "/api/s/" + site + "/cmd/stamgr";
            ResponseEntity<String> resp = rest.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                log.info("UniFi {} OK", label);
                return true;
            }
            log.warn("UniFi {} échec HTTP {}", label, resp.getStatusCode());
            return false;
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("UniFi {} HTTP {}: {}", label, e.getStatusCode(), e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.warn("UniFi {} indisponible: {}", label, e.getMessage());
            return false;
        }
    }
}

package ch.amicalewifi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.*;
import java.net.HttpURLConnection;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Intégration UniFi Cloud — création de vouchers hotspot WiFi.
 *
 * Lorsqu'un membre confirme sa présence via l'appli mobile, un voucher
 * à usage unique est généré et affiché pour qu'il se connecte au réseau
 * invité du coworking.
 *
 * API : POST {baseUrl}/api/s/{site}/cmd/hotspot
 *       Header : X-API-KEY
 *       Body   : {"cmd":"create-voucher","expire":<min>,"n":1,"quota":1,"note":"<nom>"}
 *
 * Après création, un second appel GET /api/s/{site}/stat/voucher?create_time=<ts>
 * récupère le code lisible (ex: "12345-67890").
 */
@Service
@Slf4j
public class UnifiService {

    @Value("${amicale.unifi.api-key}")               private String apiKey;
    @Value("${amicale.unifi.site:default}")          private String site;
    @Value("${amicale.unifi.base-url:https://unifi.ui.com/proxy/network}") private String baseUrl;
    @Value("${amicale.unifi.voucher-expire-minutes:600}") private int expireMinutes;

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
     * Retourne les statistiques du site depuis l'EA API (/ea/sites).
     * L'API cloud UniFi n'expose pas les clients individuels.
     */
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

    public List<String> getConnectedClientMacs() {
        return getConnectedClients().stream()
                .map(c -> (String) c.get("mac"))
                .filter(mac -> mac != null && !mac.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toList());
    }

    /** Connected clients whose MAC is not in knownMacs, with mac + hostname fields. */
    public List<Map<String, String>> getUnknownClients(Set<String> knownMacs) {
        return getConnectedClients().stream()
                .filter(c -> {
                    Object raw = c.get("mac");
                    if (!(raw instanceof String mac)) return false;
                    return !mac.isBlank() && !knownMacs.contains(mac.toLowerCase());
                })
                .map(c -> {
                    String mac      = ((String) c.get("mac")).toLowerCase();
                    Object hostnameRaw = c.get("hostname");
                    String hostname = hostnameRaw instanceof String h ? h : mac;
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("mac",      mac);
                    entry.put("hostname", hostname);
                    return entry;
                })
                .collect(Collectors.toList());
    }

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

    /** Retourne la réponse brute pour diagnostic admin. */
    public String getConnectedClientsRaw() {
        StringBuilder out = new StringBuilder();
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-KEY", apiKey);

        String hostId = "70A741F15DCF0000000006A67B3B0000000006F698BD0000000063065C68:727259026";
        String[] candidates = {
            "https://unifi.ui.com/proxy/network/api/s/default/stat/sta",
            "https://unifi.ui.com/proxy/network/api/self/sites",
            "https://unifi.ui.com/consoles/" + hostId + "/proxy/network/api/s/default/stat/sta",
            "https://unifi.ui.com/consoles/" + hostId + "/proxy/network/api/self/sites",
        };
        for (String url : candidates) {
            out.append("=== GET ").append(url).append(" ===\n");
            out.append(rawGet(url, headers)).append("\n\n");
        }
        return out.toString();
    }

    private String rawGet(String url, HttpHeaders headers) {
        try {
            ResponseEntity<String> resp = rest.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return "HTTP " + resp.getStatusCode() + "\n" + resp.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return "HTTP " + e.getStatusCode() + "\n" + e.getResponseBodyAsString();
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Crée un voucher hotspot WiFi à usage unique pour le membre.
     *
     * @param memberNote nom affiché dans le portail UniFi (ex: "Julien Reuse")
     * @return code du voucher (ex: "12345-67890"), ou empty si erreur
     */
    public Optional<String> createVoucher(String memberNote) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY", apiKey);

            // Étape 1 : créer le voucher
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cmd",    "create-voucher");
            body.put("expire", expireMinutes);
            body.put("n",      1);
            body.put("quota",  1);   // usage unique
            body.put("note",   memberNote);

            String createUrl = baseUrl + "/api/s/" + site + "/cmd/hotspot";
            ResponseEntity<Map> createResp = rest.exchange(
                    createUrl, HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);

            if (!createResp.getStatusCode().is2xxSuccessful() || createResp.getBody() == null) {
                log.warn("UniFi create-voucher échoué: {}", createResp.getStatusCode());
                return Optional.empty();
            }

            // Récupérer le create_time pour chercher le voucher
            List<?> data = (List<?>) createResp.getBody().get("data");
            if (data == null || data.isEmpty()) {
                log.warn("UniFi create-voucher: réponse vide");
                return Optional.empty();
            }
            Object createTime = ((Map<?, ?>) data.get(0)).get("create_time");
            if (createTime == null) {
                log.warn("UniFi create-voucher: create_time manquant");
                return Optional.empty();
            }

            // Étape 2 : récupérer le code du voucher
            String statUrl = baseUrl + "/api/s/" + site + "/stat/voucher?create_time=" + createTime;
            ResponseEntity<Map> statResp = rest.exchange(
                    statUrl, HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);

            if (!statResp.getStatusCode().is2xxSuccessful() || statResp.getBody() == null) {
                log.warn("UniFi stat/voucher échoué: {}", statResp.getStatusCode());
                return Optional.empty();
            }

            List<?> vouchers = (List<?>) statResp.getBody().get("data");
            if (vouchers == null || vouchers.isEmpty()) {
                log.warn("UniFi: aucun voucher trouvé pour create_time={}", createTime);
                return Optional.empty();
            }

            String code = (String) ((Map<?, ?>) vouchers.get(0)).get("code");
            if (code != null && code.length() == 10) {
                // Formater "12345-67890" si renvoyé sans tiret
                code = code.substring(0, 5) + "-" + code.substring(5);
            }
            log.info("UniFi voucher créé: {} pour {}", code, memberNote);
            return Optional.ofNullable(code);

        } catch (Exception e) {
            log.warn("UniFi voucher non disponible: {}", e.getMessage());
            return Optional.empty();
        }
    }
}

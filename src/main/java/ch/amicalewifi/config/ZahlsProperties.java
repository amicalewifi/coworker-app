package ch.amicalewifi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "amicale.zahls")
public class ZahlsProperties {
    private Map<String, String> gateways = new HashMap<>();

    public Map<String, String> getGateways() { return gateways; }
    public void setGateways(Map<String, String> gateways) { this.gateways = gateways; }
}

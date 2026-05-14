package ch.amicalewifi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

@Component
@Slf4j
public class StartupLogger {

    @Value("${server.port:8081}")
    private int port;

    @Value("${amicale.akuvox.api-key}")
    private String akuvoxApiKey;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String localIp = detectLocalIp();
        log.info("=======================================================");
        log.info("  Amicale du Wifi — application démarrée");
        log.info("  IP locale : {}", localIp);
        log.info("  URL AKUVOX à configurer sur l'A05S :");
        log.info("  http://{}:{}/api/v1/akuvox/access?apiKey={}", localIp, port, akuvoxApiKey);
        log.info("=======================================================");
    }

    private String detectLocalIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr.isSiteLocalAddress()
                            && addr.getHostAddress().indexOf(':') == -1) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
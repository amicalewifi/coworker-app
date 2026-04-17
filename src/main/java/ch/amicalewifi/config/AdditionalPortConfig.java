package ch.amicalewifi.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdditionalPortConfig {

    @Value("${amicale.akuvox.http-port:80}")
    private int akuvoxPort;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> additionalPort() {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(akuvoxPort);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}

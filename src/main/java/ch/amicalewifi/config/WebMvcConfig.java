package ch.amicalewifi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    // Pas d'intercepteur : la liaison MAC se fait au login (LoginSuccessHandler)
    // à partir des paramètres du portail captif UniFi.
}

package ch.amicalewifi.config;

import ch.amicalewifi.security.WifiMacInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final WifiMacInterceptor wifiMacInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // WifiMacInterceptor supprimé : la détection MAC se fait silencieusement
        // au login (LoginSuccessHandler). L'enregistrement manuel est accessible
        // depuis le profil mobile.
    }
}

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
        registry.addInterceptor(wifiMacInterceptor)
                .addPathPatterns("/mobile/**")
                .excludePathPatterns(
                        "/mobile/register-device",
                        "/mobile/register-device/select",
                        "/mobile/register-device/skip"
                );
    }
}

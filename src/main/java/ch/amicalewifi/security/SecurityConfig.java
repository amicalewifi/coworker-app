package ch.amicalewifi.security;

import ch.amicalewifi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository    userRepo;
    private final LoginSuccessHandler loginSuccessHandler;
    private final LoginFailureHandler loginFailureHandler;
    private final CaptivePortalParamFilter captivePortalParamFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .addFilterBefore(captivePortalParamFilter, UsernamePasswordAuthenticationFilter.class)
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/logout"))
            .authorizeHttpRequests(auth -> auth
                // Ressources publiques
                .requestMatchers("/login", "/register", "/forgot-password", "/reset-password",
                                 "/verify-email", "/resend-verification",
                                 "/css/**", "/js/**", "/icons/**",
                                 "/images/**", "/manifest.json", "/sw.js", "/error").permitAll()
                // Portail captif UniFi — pré-auth pour capturer les params puis rediriger sur /login
                .requestMatchers("/guest/**").permitAll()
                // Admin uniquement
                .requestMatchers("/admin/**").hasRole("ADMIN")
                // Mobile: membres et admin
                .requestMatchers("/mobile/**").hasAnyRole("ADMIN","COWORKER")
                // Cafétéria: membres et admin
                .requestMatchers("/cafeteria/**").hasAnyRole("ADMIN","COWORKER")
                // Borne Akuvox A05S — auth par clé API, pas de session
                .requestMatchers("/api/v1/akuvox/**").permitAll()
                // claudine-proxy (imprimante virtuelle Claudine) — auth par clé partagée, pas de session
                .requestMatchers("/api/v1/print/**").permitAll()
                // Polling dashboard entrées du jour — lecture seule, page déjà protégée
                .requestMatchers("/api/v1/admin/entries-today").permitAll()
                // Webhook zahls.ch — appelé par les serveurs Payrexx, pas de session
                .requestMatchers("/api/v1/zahls/**").permitAll()
                // Actuator health — utilisé par le monitor app-healthcheck du
                // VPS (coworker-deploy). Sans `permitAll`, Spring redirige
                // vers /login (302) et le monitor ne peut pas distinguer
                // "app vivante mais DB down" d'"app vivante normale".
                // /actuator/info et /actuator/metrics restent protégés.
                .requestMatchers("/actuator/health").permitAll()
                // API REST
                .requestMatchers("/api/**").hasAnyRole("ADMIN","COWORKER")
                // QR Code — pour les salles de conférence
                .requestMatchers("/qr/**").hasAnyRole("ADMIN","COWORKER")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler(loginSuccessHandler)
                .failureHandler(loginFailureHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            );

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return email -> {
            var user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("Utilisateur introuvable: " + email));
            // Note: we no longer block login on !emailVerified — unverified
            // users can sign in and are nagged via a banner. Otherwise a new
            // member sitting on the coworking WiFi would be stuck (can't reach
            // their inbox via the captive portal until they're authorised, but
            // can't be authorised until verified).
            return User.builder()
                    .username(user.getEmail())
                    .password(user.getPasswordHash())
                    .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                    .disabled(!user.isActive())
                    .build();
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CaptivePortalParamFilter est un @Component (Spring Boot l'enregistre
     * automatiquement sur /*). Or on l'ajoute aussi dans la chaîne Spring
     * Security via addFilterBefore. On désactive l'auto-enregistrement
     * servlet pour éviter une double exécution.
     */
    @Bean
    public FilterRegistrationBean<CaptivePortalParamFilter> captivePortalRegistration(
            CaptivePortalParamFilter filter) {
        FilterRegistrationBean<CaptivePortalParamFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}

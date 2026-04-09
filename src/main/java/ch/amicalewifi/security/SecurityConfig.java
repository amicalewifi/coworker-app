package ch.amicalewifi.security;

import ch.amicalewifi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
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

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepo;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .authorizeHttpRequests(auth -> auth
                // Ressources publiques
                .requestMatchers("/login", "/register", "/css/**", "/js/**", "/error").permitAll()
                // Borne: ADMIN ou TERMINAL
                .requestMatchers("/borne/**").hasAnyRole("ADMIN","TERMINAL")
                // Admin uniquement
                .requestMatchers("/admin/**").hasRole("ADMIN")
                // Mobile: membres et admin
                .requestMatchers("/mobile/**").hasAnyRole("ADMIN","MEMBER")
                // Cafétéria: membres et admin
                .requestMatchers("/cafeteria/**").hasAnyRole("ADMIN","MEMBER")
                // API REST
                .requestMatchers("/api/v1/scan/**").hasAnyRole("ADMIN","TERMINAL")
                .requestMatchers("/api/**").hasAnyRole("ADMIN","MEMBER","TERMINAL")
                // QR Code
                .requestMatchers("/qr/**").hasAnyRole("ADMIN","MEMBER")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error")
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

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}

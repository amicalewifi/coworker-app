package ch.amicalewifi.controller;

import ch.amicalewifi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Injects the authenticated user's email-verified status into every view
 * model. Templates render a "vérifiez votre email" banner whenever
 * {@code unverified} is true. Avoids having every controller method
 * remember to add this attribute.
 */
@ControllerAdvice(basePackages = "ch.amicalewifi.controller")
@RequiredArgsConstructor
public class MobileModelAdvice {

    private final UserRepository userRepo;

    @ModelAttribute("unverified")
    public boolean unverified(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        return userRepo.findByEmail(auth.getName())
                .map(u -> !u.isEmailVerified())
                .orElse(false);
    }
}

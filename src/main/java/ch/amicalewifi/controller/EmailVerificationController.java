package ch.amicalewifi.controller;

import ch.amicalewifi.model.EmailVerificationToken;
import ch.amicalewifi.model.Member;
import ch.amicalewifi.model.User;
import ch.amicalewifi.repository.EmailVerificationTokenRepository;
import ch.amicalewifi.repository.MemberRepository;
import ch.amicalewifi.repository.UserRepository;
import ch.amicalewifi.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationController {

    private final UserRepository                   userRepo;
    private final MemberRepository                 memberRepo;
    private final EmailVerificationTokenRepository tokenRepo;
    private final EmailService                     emailService;

    @GetMapping("/verify-email")
    public String verify(@RequestParam String token,
                         Model model,
                         RedirectAttributes ra) {
        var evt = tokenRepo.findByTokenAndUsedFalse(token)
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()));
        if (evt.isEmpty()) {
            // Lien invalide / expiré / déjà utilisé : page d'erreur avec formulaire de renvoi.
            return "auth/verify-email-error";
        }
        EmailVerificationToken t = evt.get();
        User user = userRepo.findById(t.getUser().getId()).orElseThrow();
        user.setEmailVerified(true);
        userRepo.save(user);
        t.setUsed(true);
        tokenRepo.save(t);

        // Email de bienvenue post-vérification (envoyé une seule fois).
        memberRepo.findByEmail(user.getEmail())
                .map(Member::getFirstName)
                .ifPresent(firstName -> emailService.sendWelcome(user.getEmail(), firstName));

        log.info("Email vérifié pour {}", user.getEmail());
        ra.addFlashAttribute("success",
                "Votre adresse email est vérifiée. Connectez-vous pour accéder à votre espace.");
        return "redirect:/login?verified=ok";
    }

    /**
     * Renvoyer le lien de vérification quand on est connecté (bandeau dans l'app).
     * On déduit l'email de la session — pas besoin de form param.
     */
    @PostMapping("/mobile/resend-verification")
    public String resendForCurrentUser(Authentication auth, RedirectAttributes ra) {
        String email = auth.getName();
        userRepo.findByEmail(email).ifPresent(user -> {
            if (user.isEmailVerified()) return;
            tokenRepo.deleteByUser(user);
            EmailVerificationToken evt = tokenRepo.save(EmailVerificationToken.builder()
                    .user(user)
                    .token(UUID.randomUUID().toString())
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build());
            String firstName = memberRepo.findByEmail(email)
                    .map(Member::getFirstName)
                    .orElse("");
            emailService.sendVerification(email, firstName, evt.getToken());
        });
        ra.addFlashAttribute("success",
                "Un nouveau lien de vérification vient d'être envoyé à " + email + ".");
        return "redirect:/mobile/";
    }

    @PostMapping("/resend-verification")
    public String resend(@RequestParam String email, RedirectAttributes ra) {
        // Aucun feedback distinct entre "email inconnu" et "déjà vérifié" pour ne pas
        // divulguer l'existence d'un compte.
        userRepo.findByEmail(email).ifPresent(user -> {
            if (user.isEmailVerified()) return;
            tokenRepo.deleteByUser(user);
            EmailVerificationToken evt = tokenRepo.save(EmailVerificationToken.builder()
                    .user(user)
                    .token(UUID.randomUUID().toString())
                    .expiresAt(LocalDateTime.now().plusHours(24))
                    .build());
            String firstName = memberRepo.findByEmail(user.getEmail())
                    .map(Member::getFirstName)
                    .orElse("");
            emailService.sendVerification(user.getEmail(), firstName, evt.getToken());
        });
        ra.addFlashAttribute("success",
                "Si un compte non vérifié existe pour cet email, un nouveau lien vient d'être envoyé.");
        return "redirect:/login";
    }
}

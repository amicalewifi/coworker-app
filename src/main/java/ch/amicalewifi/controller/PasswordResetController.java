package ch.amicalewifi.controller;

import ch.amicalewifi.model.PasswordResetToken;
import ch.amicalewifi.repository.PasswordResetTokenRepository;
import ch.amicalewifi.repository.UserRepository;
import ch.amicalewifi.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class PasswordResetController {

    private final UserRepository               userRepo;
    private final PasswordResetTokenRepository tokenRepo;
    private final EmailService                 emailService;
    private final PasswordEncoder              passwordEncoder;

    @GetMapping("/forgot-password")
    public String forgotForm() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotSubmit(@RequestParam String email, RedirectAttributes ra) {
        userRepo.findByEmail(email).ifPresent(user -> {
            tokenRepo.deleteByUser(user);
            PasswordResetToken prt = tokenRepo.save(PasswordResetToken.builder()
                    .user(user)
                    .token(UUID.randomUUID().toString())
                    .expiresAt(LocalDateTime.now().plusHours(1))
                    .build());
            emailService.sendPasswordReset(email, prt.getToken());
        });
        ra.addFlashAttribute("success",
                "Si un compte existe pour cet email, un lien de réinitialisation a été envoyé.");
        return "redirect:/login";
    }

    @GetMapping("/reset-password")
    public String resetForm(@RequestParam String token, Model model) {
        var prt = tokenRepo.findByTokenAndUsedFalse(token)
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()));
        if (prt.isEmpty()) {
            model.addAttribute("error", "Ce lien est invalide ou expiré.");
            return "auth/reset-password";
        }
        model.addAttribute("token", token);
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String resetSubmit(@RequestParam String token,
                              @RequestParam String password,
                              @RequestParam String confirmPassword,
                              Model model,
                              RedirectAttributes ra) {
        var optPrt = tokenRepo.findByTokenAndUsedFalse(token)
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()));
        if (optPrt.isEmpty()) {
            model.addAttribute("error", "Ce lien est invalide ou expiré.");
            model.addAttribute("token", token);
            return "auth/reset-password";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("error", "Les mots de passe ne correspondent pas.");
            model.addAttribute("token", token);
            return "auth/reset-password";
        }
        if (password.length() < 8) {
            model.addAttribute("error", "Le mot de passe doit contenir au moins 8 caractères.");
            model.addAttribute("token", token);
            return "auth/reset-password";
        }
        var prt = optPrt.get();
        var user = userRepo.findById(prt.getUser().getId()).orElseThrow();
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepo.save(user);
        prt.setUsed(true);
        tokenRepo.save(prt);
        ra.addFlashAttribute("success", "Mot de passe réinitialisé. Connecte-toi.");
        return "redirect:/login";
    }
}

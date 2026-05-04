package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import ch.amicalewifi.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/register")
@RequiredArgsConstructor
@Slf4j
public class RegisterController {

    private final UserRepository   userRepo;
    private final MemberRepository memberRepo;
    private final MemberService    memberService;
    private final PasswordEncoder  passwordEncoder;

    @GetMapping
    public String form() {
        return "auth/register";
    }

    @PostMapping
    public String submit(@RequestParam String firstName,
                         @RequestParam String lastName,
                         @RequestParam String email,
                         @RequestParam String password,
                         @RequestParam(required = false) String phone,
                         @RequestParam(required = false) String company,
                         @RequestParam(required = false) String address,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) String postalCode,
                         @RequestParam(required = false) String country,
                         Model model,
                         RedirectAttributes ra) {

        if (userRepo.existsByEmail(email) || memberRepo.existsByEmail(email)) {
            model.addAttribute("error", "Un compte existe déjà avec cet email.");
            return "auth/register";
        }

        User user = null;
        try {
            user = userRepo.save(User.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode(password))
                    .role(UserRole.COWORKER)
                    .build());

            memberService.create(Member.builder()
                    .firstName(firstName).lastName(lastName)
                    .email(email).phone(phone).company(company)
                    .address(address).city(city)
                    .postalCode(postalCode)
                    .country(country != null && !country.isBlank() ? country : "Suisse")
                    .membership(MembershipType.JOURNEE_ESSAI)
                    .user(user)
                    .active(true)
                    .build());

            log.info("Nouvelle inscription: {} {} ({})", firstName, lastName, email);
            ra.addFlashAttribute("success",
                    "Bienvenue " + firstName + " ! Votre compte a été créé. Connectez-vous pour accéder à votre espace.");
            return "redirect:/login?registered";

        } catch (Exception e) {
            log.error("Erreur lors de l'inscription de {}: {}", email, e.getMessage(), e);
            // Nettoyer l'utilisateur orphelin si le membre n'a pas pu être créé
            if (user != null) {
                try { userRepo.delete(user); } catch (Exception ignored) {}
            }
            model.addAttribute("error", "Une erreur est survenue lors de la création du compte : " + e.getMessage());
            return "auth/register";
        }
    }
}

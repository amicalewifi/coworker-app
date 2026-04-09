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
    public String form(Model model) {
        model.addAttribute("memberships", MembershipType.values());
        return "auth/register";
    }

    @PostMapping
    public String submit(@RequestParam String firstName,
                         @RequestParam String lastName,
                         @RequestParam String email,
                         @RequestParam String password,
                         @RequestParam(required = false) String phone,
                         @RequestParam MembershipType membership,
                         RedirectAttributes ra) {

        if (userRepo.existsByEmail(email) || memberRepo.existsByEmail(email)) {
            ra.addFlashAttribute("error", "Un compte existe déjà avec cet email.");
            return "redirect:/register";
        }

        User user = userRepo.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .role(UserRole.MEMBER)
                .build());

        memberService.create(Member.builder()
                .firstName(firstName).lastName(lastName)
                .email(email).phone(phone)
                .membership(membership)
                .user(user)
                .active(true)
                .build());

        log.info("Nouvelle inscription: {} {} ({})", firstName, lastName, email);
        ra.addFlashAttribute("success",
                "Bienvenue " + firstName + " ! Votre compte a été créé. Connectez-vous pour accéder à votre espace.");
        return "redirect:/login?registered";
    }
}

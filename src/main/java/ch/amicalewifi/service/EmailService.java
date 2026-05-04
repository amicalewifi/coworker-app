package ch.amicalewifi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${amicale.mail.from}") private String from;
    @Value("${amicale.mail.base-url}") private String baseUrl;

    @Async
    public void sendWelcome(String to, String firstName) {
        String subject = "Bienvenue à l'Amicale du WiFi !";
        String html = """
            <div style="font-family:sans-serif;max-width:520px;margin:0 auto;">
              <h2 style="color:#0c1222;">Bienvenue %s !</h2>
              <p>Votre compte membre a été créé avec succès.</p>
              <p>Vous pouvez maintenant vous connecter et acheter votre pack dans votre espace membre.</p>
              <a href="%s/mobile/" style="display:inline-block;margin-top:16px;padding:12px 24px;background:#007af5;color:#fff;border-radius:8px;text-decoration:none;font-weight:700;">
                Accéder à mon espace
              </a>
              <p style="margin-top:24px;color:#666;font-size:13px;">
                l'Amicale du WiFi · Fully Coworking · CH-1926
              </p>
            </div>
            """.formatted(firstName, baseUrl);
        send(to, subject, html);
    }

    @Async
    public void sendPasswordReset(String to, String token) {
        String link = baseUrl + "/reset-password?token=" + token;
        String subject = "Réinitialisation de votre mot de passe";
        String html = """
            <div style="font-family:sans-serif;max-width:520px;margin:0 auto;">
              <h2 style="color:#0c1222;">Réinitialisation du mot de passe</h2>
              <p>Vous avez demandé la réinitialisation de votre mot de passe.</p>
              <p>Ce lien est valable <strong>1 heure</strong>.</p>
              <a href="%s" style="display:inline-block;margin-top:16px;padding:12px 24px;background:#007af5;color:#fff;border-radius:8px;text-decoration:none;font-weight:700;">
                Réinitialiser mon mot de passe
              </a>
              <p style="margin-top:16px;color:#666;font-size:12px;">
                Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.
              </p>
              <p style="color:#666;font-size:13px;">
                l'Amicale du WiFi · Fully Coworking · CH-1926
              </p>
            </div>
            """.formatted(link);
        send(to, subject, html);
    }

    private void send(String to, String subject, String html) {
        try {
            var msg = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(msg, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
        } catch (Exception e) {
            log.error("Échec envoi email à {}: {}", to, e.getMessage());
        }
    }
}

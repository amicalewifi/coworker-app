package ch.amicalewifi.controller;

import ch.amicalewifi.model.Member;
import ch.amicalewifi.repository.MemberRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;

@RestController
@RequestMapping("/qr")
@RequiredArgsConstructor
public class QrController {

    private final MemberRepository memberRepo;

    /** QR code de salle. */
    @GetMapping(value = "/room/{token}", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] roomQr(
            @PathVariable String token,
            @RequestHeader(value = "Host", defaultValue = "localhost:8081") String host) throws Exception {
        return toQr("http://" + host + "/mobile/room-scan?token=" + token, 300);
    }

    /**
     * QR code d'accès porte du membre connecté. Encode `members.badge_uid`
     * tel quel — c'est ce que l'Akuvox A05S envoie comme cardNo, peu importe
     * que la lecture soit NFC ou caméra QR.
     *
     * 404 si le membre n'a pas encore de badge_uid (à activer manuellement
     * via l'admin). Cache-Control no-store car le contenu est l'équivalent
     * d'une clé physique.
     */
    @GetMapping(value = "/door", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> doorQr(Authentication auth) throws Exception {
        Member m = memberRepo.findByEmail(auth.getName()).orElseThrow();
        if (m.getBadgeUid() == null || m.getBadgeUid().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toQr(m.getBadgeUid(), 440));
    }

    private byte[] toQr(String content, int size) throws Exception {
        var matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
        var img    = MatrixToImageWriter.toBufferedImage(matrix);
        var out    = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        return out.toByteArray();
    }
}

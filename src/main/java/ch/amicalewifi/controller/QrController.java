package ch.amicalewifi.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;

@RestController
@RequestMapping("/qr")
@RequiredArgsConstructor
public class QrController {

    /** QR code de salle. */
    @GetMapping(value = "/room/{token}", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] roomQr(
            @PathVariable String token,
            @RequestHeader(value = "Host", defaultValue = "localhost:8081") String host) throws Exception {
        return toQr("http://" + host + "/mobile/room-scan?token=" + token, 300);
    }

    private byte[] toQr(String content, int size) throws Exception {
        var matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
        var img    = MatrixToImageWriter.toBufferedImage(matrix);
        var out    = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        return out.toByteArray();
    }
}

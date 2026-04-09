package ch.amicalewifi.controller;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;

@RestController
@RequestMapping("/qr")
public class QrController {

    @GetMapping(value = "/member/{uid}", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] memberQr(
            @PathVariable String uid,
            @RequestParam(defaultValue = "FULL_DAY") String type,
            @RequestHeader(value = "Host", defaultValue = "localhost:8080") String host) throws Exception {
        return toQr("http://" + host + "/mobile/?presenceType=" + type + "&badgeUid=" + uid);
    }

    @GetMapping(value = "/room/{token}", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] roomQr(
            @PathVariable String token,
            @RequestHeader(value = "Host", defaultValue = "localhost:8080") String host) throws Exception {
        return toQr("http://" + host + "/mobile/room-scan?token=" + token);
    }

    private byte[] toQr(String content) throws Exception {
        var matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300);
        var img    = MatrixToImageWriter.toBufferedImage(matrix);
        var out    = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        return out.toByteArray();
    }
}

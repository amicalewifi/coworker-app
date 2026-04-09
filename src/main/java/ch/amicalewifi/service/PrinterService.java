package ch.amicalewifi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Service d'impression RAW vers Kyocera TASKalfa 352ci (port 9100).
 */
@Service
@Slf4j
public class PrinterService {

    @Value("${amicale.printer.host:192.168.1.10}")
    private String host;

    @Value("${amicale.printer.port:9100}")
    private int port;

    @Value("${amicale.printer.timeout-ms:2000}")
    private int timeoutMs;

    /** Vérifie la joignabilité de l'imprimante via TCP. */
    public boolean isOnline() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            log.debug("Imprimante {}:{} — en ligne", host, port);
            return true;
        } catch (IOException e) {
            log.debug("Imprimante {}:{} — hors ligne: {}", host, port, e.getMessage());
            return false;
        }
    }

    /**
     * Envoie un fichier à l'imprimante via le port RAW 9100 avec en-tête PJL.
     * Le PJL (Printer Job Language) indique à la Kyocera le format du document
     * — sans quoi elle interprète les octets PDF comme du texte PCL.
     *
     * @param data     contenu du fichier (PDF, PS, PCL…)
     * @param filename nom affiché dans la file d'impression
     * @param language langage PDL : "PDF", "POSTSCRIPT", "PCL" (défaut : PDF)
     * @throws IOException si la connexion ou l'envoi échoue
     */
    public void print(byte[] data, String filename, String language) throws IOException {
        // UEL = Universal Exit Language : séquence de réinitialisation Kyocera
        String uel = "\033%-12345X";

        String pjlHeader = uel
                + "@PJL JOB NAME=\"" + sanitize(filename) + "\"\r\n"
                + "@PJL SET COPIES=1\r\n"
                + "@PJL ENTER LANGUAGE=" + language + "\r\n";

        String pjlFooter = "\r\n" + uel + "@PJL EOJ\r\n" + uel;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(pjlHeader.getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.write(pjlFooter.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            log.info("Impression envoyée: {} ({} octets, langage={}) → {}:{}", filename, data.length, language, host, port);
        }
    }

    /** Surcharge pour PDF (cas le plus courant). */
    public void print(byte[] data, String filename) throws IOException {
        print(data, filename, "PDF");
    }

    /** Retire les caractères non-ASCII du nom de fichier pour le PJL. */
    private String sanitize(String name) {
        if (name == null) return "document";
        return name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
    }

    public String getHost() { return host; }
    public int    getPort() { return port; }
}

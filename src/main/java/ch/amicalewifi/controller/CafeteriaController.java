package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import ch.amicalewifi.service.PrinterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDateTime;

@Controller
@RequestMapping("/cafeteria")
@RequiredArgsConstructor
@Slf4j
public class CafeteriaController {

    private final PrinterJobRepository printerRepo;
    private final MemberRepository     memberRepo;
    private final PrinterService       printerService;

    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("printing",      printerRepo.findByStatusOrderByCreatedAtAsc(PrintJobStatus.PRINTING));
        model.addAttribute("queued",        printerRepo.findByStatusOrderByCreatedAtAsc(PrintJobStatus.QUEUED));
        model.addAttribute("printerOnline", printerService.isOnline());
        model.addAttribute("printerHost",   printerService.getHost());
        return "cafeteria/index";
    }

    @PostMapping("/print")
    public String print(Authentication auth,
                        @RequestParam("file") MultipartFile file,
                        @RequestParam(defaultValue = "1") int copies,
                        @RequestParam(defaultValue = "false") boolean color,
                        @RequestParam(defaultValue = "true")  boolean duplex,
                        RedirectAttributes ra) {

        if (file.isEmpty()) {
            ra.addFlashAttribute("error", "Veuillez sélectionner un fichier.");
            return "redirect:/cafeteria/";
        }

        Member member = memberRepo.findByEmail(auth.getName()).orElse(null);

        // Détecter le nombre de pages
        int pages = detectPages(file);

        String filename = file.getOriginalFilename();
        boolean online  = printerService.isOnline();

        PrinterJob job = printerRepo.save(PrinterJob.builder()
                .member(member).filename(filename)
                .pages(pages).copies(copies).color(color).duplex(duplex)
                .status(online ? PrintJobStatus.PRINTING : PrintJobStatus.QUEUED)
                .build());

        if (online) {
            try {
                byte[] data = file.getBytes();
                printerService.print(data, filename, "PDF", copies, duplex);

                job.setStatus(PrintJobStatus.COMPLETED);
                job.setCompletedAt(LocalDateTime.now());
                printerRepo.save(job);

                log.info("Impression envoyée: {} ({} pages) pour {}", filename, job.getTotalPages(),
                        member != null ? member.getDisplayName() : "anonyme");
                ra.addFlashAttribute("success",
                        "«" + filename + "» envoyé à l'imprimante — " + job.getTotalPages() + " page(s).");
            } catch (IOException e) {
                log.error("Échec envoi imprimante: {}", e.getMessage());
                job.setStatus(PrintJobStatus.QUEUED);
                job.setErrorMessage(e.getMessage());
                printerRepo.save(job);
                ra.addFlashAttribute("error", "Erreur imprimante — fichier mis en file d'attente.");
            }
        } else {
            ra.addFlashAttribute("error",
                    "Imprimante hors ligne — «" + filename + "» mis en file d'attente.");
        }

        return "redirect:/cafeteria/";
    }

    /** Détecte le nombre de pages d'un PDF. Retourne 1 pour les autres formats. */
    private int detectPages(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name != null && name.toLowerCase().endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
                return doc.getNumberOfPages();
            } catch (IOException e) {
                log.warn("Impossible de lire le nombre de pages du PDF: {}", e.getMessage());
            }
        }
        return 1;
    }
}

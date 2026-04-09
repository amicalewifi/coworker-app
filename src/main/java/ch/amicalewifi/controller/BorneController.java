package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/borne")
@RequiredArgsConstructor
public class BorneController {

    private final ScanService   scanService;
    private final MemberService memberService;

    @GetMapping({"", "/"})
    public String borne(Model model) {
        model.addAttribute("presenceTypes",
                List.of(PresenceType.HALF_AM, PresenceType.FULL_DAY, PresenceType.HALF_PM));
        return "borne/index";
    }

    @PostMapping("/scan")
    public String scan(@RequestParam String badgeUid,
                       @RequestParam(defaultValue = "FULL_DAY") PresenceType presenceType,
                       @RequestParam(defaultValue = "false") boolean unitaire,
                       Model model) {
        if (unitaire && !presenceType.isUnitaire()) {
            presenceType = presenceType.toUnitaire();
        }
        ScanResult result = scanService.processScan(badgeUid.trim().toUpperCase(), presenceType);
        model.addAttribute("result",       result);
        model.addAttribute("presenceType", presenceType);

        if (result instanceof ScanResult.Granted)   return "borne/result-granted";
        if (result instanceof ScanResult.Denied)    return "borne/result-denied";
        return "borne/result-new-member";
    }

    @PostMapping("/register")
    public String register(@RequestParam String firstName,
                           @RequestParam String lastName,
                           @RequestParam String email,
                           @RequestParam(required = false) String phone,
                           @RequestParam String badgeUid,
                           @RequestParam MembershipType membership,
                           @RequestParam(defaultValue = "FULL_DAY") PresenceType presenceType,
                           Model model) {
        memberService.create(Member.builder()
                .firstName(firstName).lastName(lastName).email(email)
                .phone(phone).badgeUid(badgeUid).membership(membership).build());
        ScanResult result = scanService.processScan(badgeUid, presenceType);
        model.addAttribute("result", result);
        return "borne/result-granted";
    }
}

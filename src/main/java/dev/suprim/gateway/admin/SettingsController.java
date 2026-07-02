package dev.suprim.gateway.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SettingsController {

    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("view", "settings");
        model.addAttribute("currentPage", "settings");
        model.addAttribute("pageTitle", "Settings");
        return "layout";
    }
}

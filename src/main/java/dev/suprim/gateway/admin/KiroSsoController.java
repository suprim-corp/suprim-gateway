package dev.suprim.gateway.admin;

import dev.suprim.gateway.provider.kiro.sso.KiroSsoFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class KiroSsoController {

    private final KiroSsoFacade ssoFacade;

    @PostMapping("/auth/kiro/sso/start")
    Map<String, Object> start(@RequestBody Map<String, String> body) {
        String startUrl = body.get("startUrl");
        String region = body.getOrDefault("region", "us-east-1");
        return ssoFacade.startDeviceFlow(startUrl, region);
    }

    @GetMapping("/auth/kiro/sso/poll")
    Map<String, Object> poll(@RequestParam String session) {
        return ssoFacade.pollToken(session);
    }
}

package com.diet.controller.profile;

import com.diet.auth.AuthPrincipal;
import com.diet.service.profile.DietaryProfileService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/diet/profile")
public class DietaryProfileController {
    private final DietaryProfileService profiles;

    public DietaryProfileController(DietaryProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    public DietaryProfileService.Profile get(@AuthenticationPrincipal AuthPrincipal principal) {
        return profiles.get(principal.userId());
    }

    @PutMapping
    public DietaryProfileService.Profile update(@AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody DietaryProfileService.ProfileUpdate update) {
        return profiles.save(principal.userId(), update);
    }

    @PostMapping("/skip")
    public DietaryProfileService.Profile skip(@AuthenticationPrincipal AuthPrincipal principal) {
        return profiles.skip(principal.userId());
    }
}

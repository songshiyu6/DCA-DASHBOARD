package com.dca.terminal.settings;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.dca.terminal.settings.SettingsDtos.SettingsResponse;
import static com.dca.terminal.settings.SettingsDtos.SettingsUpdateRequest;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public SettingsResponse settings() { return service.get(); }

    @PutMapping
    public SettingsResponse update(@Valid @RequestBody SettingsUpdateRequest request) { return service.update(request); }
}

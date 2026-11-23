package com.yizhaoqi.smartpai.coding;

import com.yizhaoqi.smartpai.config.AiProperties;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/coding")
public class CodingCapabilitiesController {
    private final CodingProperties config;
    private final AiProperties ai;
    public CodingCapabilitiesController(CodingProperties config,AiProperties ai) { this.config=config;this.ai=ai; }
    @GetMapping("/capabilities")
    public Map<String,Object> capabilities() {
        return Map.of("code",200,"message","success","data",Map.of("enabled",config.isEnabled() && ai.getAgent().isEnabled() && ai.getAgent().getProfiles().getCoding().isEnabled()));
    }
}

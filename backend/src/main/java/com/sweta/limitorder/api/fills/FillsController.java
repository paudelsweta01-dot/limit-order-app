package com.sweta.limitorder.api.fills;

import com.sweta.limitorder.auth.AuthenticatedUser;
import com.sweta.limitorder.fills.FillsQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/fills")
@RequiredArgsConstructor
public class FillsController {

    private final FillsQueryService fills;

    @GetMapping("/mine")
    public List<MyFillResponse> mine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return fills.findByUser(principal.userId());
    }
}

package com.sweta.limitorder.api.symbols;

import com.sweta.limitorder.persistence.SymbolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/symbols")
@RequiredArgsConstructor
public class SymbolController {

    private final SymbolRepository symbols;

    @GetMapping
    public List<SymbolResponse> list() {
        return symbols.findAll(Sort.by("symbol")).stream()
                .map(s -> new SymbolResponse(s.getSymbol(), s.getName(), s.getRefPrice()))
                .toList();
    }
}

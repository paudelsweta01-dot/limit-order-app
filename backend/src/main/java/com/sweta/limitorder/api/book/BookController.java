package com.sweta.limitorder.api.book;

import com.sweta.limitorder.book.BookQueryService;
import com.sweta.limitorder.book.BookSnapshot;
import com.sweta.limitorder.book.BookTotals;
import com.sweta.limitorder.persistence.SymbolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/book")
@RequiredArgsConstructor
public class BookController {

    private final BookQueryService books;
    private final SymbolRepository symbols;

    @GetMapping("/{symbol}")
    public BookSnapshot snapshot(@PathVariable String symbol) {
        ensureKnownSymbol(symbol);
        return books.snapshot(symbol);
    }

    @GetMapping("/{symbol}/totals")
    public BookTotals totals(@PathVariable String symbol) {
        ensureKnownSymbol(symbol);
        return books.totals(symbol);
    }

    private void ensureKnownSymbol(String symbol) {
        if (!symbols.existsById(symbol)) {
            throw new IllegalArgumentException("unknown symbol: " + symbol);
        }
    }
}

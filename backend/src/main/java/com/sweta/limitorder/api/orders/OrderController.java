package com.sweta.limitorder.api.orders;

import com.sweta.limitorder.auth.AuthenticatedUser;
import com.sweta.limitorder.matching.CancelOrderCommand;
import com.sweta.limitorder.matching.CancelResult;
import com.sweta.limitorder.matching.MatchingEngineService;
import com.sweta.limitorder.matching.OrderResult;
import com.sweta.limitorder.matching.SubmitOrderCommand;
import com.sweta.limitorder.persistence.OrderRepository;
import com.sweta.limitorder.persistence.SymbolRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface in front of {@link MatchingEngineService}. Pure delegation —
 * no business logic; controllers carry validation, DTO mapping, and
 * authentication-principal extraction (architecture §4.1 layer rule).
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final MatchingEngineService engine;
    private final OrderRepository orders;
    private final SymbolRepository symbols;

    /**
     * Submit a new order. Returns 201 on first creation, 200 on idempotent
     * replay (same {@code (userId, clientOrderId)} as a previous request).
     */
    @PostMapping
    public ResponseEntity<SubmitOrderResponse> submit(
            @Valid @RequestBody SubmitOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        if (!symbols.existsById(request.symbol())) {
            throw new IllegalArgumentException("unknown symbol: " + request.symbol());
        }

        SubmitOrderCommand cmd = new SubmitOrderCommand(
                request.clientOrderId(),
                principal.userId(),
                request.symbol(),
                request.side(),
                request.type(),
                request.price(),
                request.quantity());

        OrderResult result = engine.submit(cmd);

        SubmitOrderResponse body = new SubmitOrderResponse(
                result.orderId(),
                result.status(),
                result.filledQty(),
                result.rejectReason(),
                result.idempotentReplay());

        HttpStatus status = result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Cancel an OPEN/PARTIAL order. Ownership enforced server-side: only the
     * caller's own orders are cancellable.
     *
     * <ul>
     *   <li>200 — order successfully transitioned to CANCELLED.</li>
     *   <li>403 — caller is not the order's owner.</li>
     *   <li>404 — no such order.</li>
     *   <li>409 — order is already FILLED or CANCELLED.</li>
     * </ul>
     */
    @DeleteMapping("/{orderId}")
    public CancelOrderResponse cancel(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        CancelResult result = engine.cancel(new CancelOrderCommand(orderId, principal.userId()));
        return new CancelOrderResponse(result.orderId(), result.status(), result.filledQty());
    }

    /**
     * "My orders" — every order the authenticated user has ever submitted,
     * newest first (architecture §6.4).
     */
    @GetMapping("/mine")
    public List<MyOrderResponse> mine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return orders.findByUser(principal.userId()).stream()
                .map(MyOrderResponse::from)
                .toList();
    }
}

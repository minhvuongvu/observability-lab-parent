package com.observability.lab.inventory.api;

import com.observability.lab.inventory.application.StockApplicationService;
import com.observability.lab.inventory.application.StockLevelView;
import com.observability.lab.shared.api.ApiResponse;
import com.observability.lab.shared.api.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP access to stock levels.
 *
 * <p>{@code GET /api/v1/stock/{productSku}} is the endpoint the Order Service's Feign client calls,
 * so its path and response shape are a published contract between the two services.
 *
 * <p>The three quantity operations are POSTs to named sub-resources rather than a PATCH on the
 * stock level. Receiving, releasing and adjusting are distinct events with distinct rules and each
 * leaves its own entry in the movement trail; collapsing them into "set the number to N" would
 * discard the reason, which is the only part worth keeping.
 */
@RestController
@RequestMapping("/api/v1/stock")
@Validated
public class StockController {

    private final StockApplicationService stock;

    public StockController(StockApplicationService stock) {
        this.stock = stock;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StockLevelView>> create(
            @Valid @RequestBody CreateStockLevelRequest request) {

        StockLevelView created = stock.create(request.productSku(), request.initialQuantity());
        URI location = URI.create("/api/v1/stock/" + created.productSku());
        return ResponseEntity.created(location).body(ApiResponse.success(created));
    }

    /** Read one stock level. Served from Redis when warm. */
    @GetMapping("/{productSku}")
    public ApiResponse<StockLevelView> get(
            @PathVariable
            @NotBlank
            @Size(max = 64, message = "productSku must be at most 64 characters")
            String productSku) {

        return ApiResponse.success(stock.findByProductSku(productSku));
    }

    @GetMapping
    public ApiResponse<PageResponse<StockLevelView>> list(
            @PageableDefault(size = 20, sort = "productSku", direction = Sort.Direction.ASC)
            Pageable pageable) {

        Page<StockLevelView> page = stock.list(pageable);
        // The platform's own page shape: Spring Data's Page serialises its internals and its JSON
        // is not stable across versions, which makes it a poor public contract.
        return ApiResponse.success(PageResponse.of(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements()));
    }

    /** Units arrived and become available. */
    @PostMapping("/{productSku}/receive")
    public ApiResponse<StockLevelView> receive(
            @PathVariable @NotBlank @Size(max = 64) String productSku,
            @Valid @RequestBody StockQuantityRequest request) {

        return ApiResponse.success(
                stock.receive(productSku, request.quantity(), request.reference()));
    }

    /** Undo a reservation, normally because an order was cancelled. */
    @PostMapping("/{productSku}/release")
    public ApiResponse<StockLevelView> release(
            @PathVariable @NotBlank @Size(max = 64) String productSku,
            @Valid @RequestBody StockQuantityRequest request) {

        return ApiResponse.success(
                stock.release(productSku, request.quantity(), request.reference()));
    }

    /** Operator correction, for example after a physical count. */
    @PostMapping("/{productSku}/adjust")
    public ApiResponse<StockLevelView> adjust(
            @PathVariable @NotBlank @Size(max = 64) String productSku,
            @Valid @RequestBody StockQuantityRequest request) {

        return ApiResponse.success(
                stock.adjust(productSku, request.quantity(), request.reference()));
    }

    /**
     * Stop tracking a product.
     *
     * <p>Returns 200 with the standard envelope rather than 204, because every response in this API
     * carries the trace id in its meta block.
     */
    @DeleteMapping("/{productSku}")
    public ApiResponse<Void> delete(
            @PathVariable @NotBlank @Size(max = 64) String productSku) {

        stock.delete(productSku);
        return ApiResponse.successNoContent();
    }
}

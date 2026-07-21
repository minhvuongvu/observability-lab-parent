package com.observability.lab.order.api;

import com.observability.lab.order.application.AvailabilityService;
import com.observability.lab.order.application.AvailabilityView;
import com.observability.lab.order.application.InvoiceLink;
import com.observability.lab.order.application.OrderApplicationService;
import com.observability.lab.order.application.OrderView;
import com.observability.lab.order.domain.OrderStatus;
import com.observability.lab.shared.api.ApiResponse;
import com.observability.lab.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP access to the order lifecycle.
 *
 * <p>The controller does three things and nothing else: bind and validate input, call one use case,
 * wrap the result. There is no business logic here and no exception handling — failures propagate to
 * the platform handler in the shared library, which owns the mapping from failure category to status
 * code and log level.
 *
 * <p>Orders are addressed by their order number throughout. The surrogate database id never appears
 * in a URL, so it stays free to change.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Validated
@Tag(name = "Orders", description = "Place, inspect, cancel and remove orders")
public class OrderController {

    private final OrderApplicationService orders;
    private final AvailabilityService availability;

    public OrderController(OrderApplicationService orders, AvailabilityService availability) {
        this.orders = orders;
        this.availability = availability;
    }

    @PostMapping
    @Operation(summary = "Place an order",
            description = "Accepts the order as PENDING and publishes order-created. Stock is "
                    + "reserved asynchronously, so a 201 means accepted, not fulfilled.")
    public ResponseEntity<ApiResponse<OrderView>> create(
            @Valid @RequestBody CreateOrderRequest request) {

        OrderView created = orders.create(request.toCommand());
        // Location points at the canonical URL of what was just made, so a client never has to
        // assemble it from the response body.
        URI location = URI.create("/api/v1/orders/" + created.orderNumber());
        return ResponseEntity.created(location).body(ApiResponse.success(created));
    }

    @GetMapping("/{orderNumber}")
    @Operation(summary = "Read one order", description = "Served from the cache when warm.")
    public ApiResponse<OrderView> get(
            @PathVariable
            @NotBlank
            @Size(max = 40, message = "orderNumber must be at most 40 characters")
            String orderNumber) {

        return ApiResponse.success(orders.findByOrderNumber(orderNumber));
    }

    @GetMapping
    @Operation(summary = "List orders",
            description = "Newest first. Both filters are optional and combine with AND.")
    public ApiResponse<PageResponse<OrderView>> list(
            @Parameter(description = "Restrict to one customer")
            @RequestParam(required = false) @Size(max = 64) String customerId,

            @Parameter(description = "Restrict to one status")
            @RequestParam(required = false) OrderStatus status,

            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        Page<OrderView> page = orders.search(customerId, status, pageable);
        // Converted to the platform's own page shape: Spring Data's Page serialises its internals
        // and its JSON is not stable across versions, which makes it a poor public contract.
        return ApiResponse.success(PageResponse.of(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements()));
    }

    @PostMapping("/availability")
    @Operation(summary = "Check stock before placing an order",
            description = "Advisory only. Calls the Inventory Service synchronously — resolved "
                    + "through the Consul registry — and reports what is available right now. "
                    + "Nothing is reserved: the authoritative decision is made asynchronously after "
                    + "the order is placed, so a 'sufficient' answer here can still be overtaken.")
    public ApiResponse<List<AvailabilityView>> availability(
            @Valid @RequestBody CreateOrderRequest request) {

        return ApiResponse.success(availability.check(request.toCommand().items()));
    }

    @GetMapping("/{orderNumber}/invoice")
    @Operation(summary = "Get a link to the order's invoice",
            description = "Returns a short-lived signed URL to the invoice in object storage rather "
                    + "than the document itself, so the download never passes through this service. "
                    + "The invoice is rebuilt on the spot if it is not already archived.")
    public ApiResponse<InvoiceLink> invoice(
            @PathVariable @NotBlank @Size(max = 40) String orderNumber) {

        return ApiResponse.success(orders.invoiceFor(orderNumber));
    }

    @PostMapping("/{orderNumber}/cancel")
    @Operation(summary = "Cancel an order",
            description = "A POST rather than a PATCH: cancelling is an action with rules, not a "
                    + "field being edited. Refused with 422 if the status does not allow it.")
    public ApiResponse<OrderView> cancel(
            @PathVariable @NotBlank @Size(max = 40) String orderNumber) {

        return ApiResponse.success(orders.cancel(orderNumber));
    }

    @DeleteMapping("/{orderNumber}")
    @Operation(summary = "Delete an order",
            description = "Permitted only once the order is CANCELLED or REJECTED. Returns 200 with "
                    + "the standard envelope rather than 204, because every response in this API "
                    + "carries the trace id in its meta block.")
    public ApiResponse<Void> delete(
            @PathVariable @NotBlank @Size(max = 40) String orderNumber) {

        orders.delete(orderNumber);
        return ApiResponse.successNoContent();
    }
}

package com.ashish.distributed_order_platform.order.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The total is deliberately absent: the server computes it, so a client can never set its own price. */
public record CreateOrderRequest(
		@NotNull UUID customerId,
		@NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter uppercase ISO-4217 code") String currency,
		@NotEmpty @Size(max = 100) List<@NotNull @Valid OrderItemRequest> items) {
}

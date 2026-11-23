package com.ashish.distributed_order_platform.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ashish.distributed_order_platform.order.entity.Order;
import com.ashish.distributed_order_platform.order.entity.OrderStatus;

public record OrderResponse(
		UUID orderId,
		UUID customerId,
		OrderStatus status,
		BigDecimal totalAmount,
		String currency,
		List<OrderItemResponse> items,
		Instant createdAt,
		Instant updatedAt,
		long version) {

	/** Must run inside a transaction: it reads the lazy items collection. */
	public static OrderResponse from(Order order) {
		return new OrderResponse(
				order.getId(),
				order.getCustomerId(),
				order.getStatus(),
				order.getTotalAmount(),
				order.getCurrency(),
				order.getItems().stream().map(OrderItemResponse::from).toList(),
				order.getCreatedAt(),
				order.getUpdatedAt(),
				order.getVersion());
	}
}

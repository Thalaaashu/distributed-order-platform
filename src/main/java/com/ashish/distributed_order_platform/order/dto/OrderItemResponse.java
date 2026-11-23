package com.ashish.distributed_order_platform.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.ashish.distributed_order_platform.order.entity.OrderItem;

public record OrderItemResponse(UUID productId, int quantity, BigDecimal unitPrice) {

	static OrderItemResponse from(OrderItem item) {
		return new OrderItemResponse(item.getProductId(), item.getQuantity(), item.getUnitPrice());
	}
}

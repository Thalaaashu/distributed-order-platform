package com.ashish.distributed_order_platform.order.entity;

/**
 * Persisted by name (EnumType.STRING), so values can be added or reordered safely.
 * Further states (CONFIRMED, SHIPPED, ...) arrive with the saga work.
 */
public enum OrderStatus {
	CREATED,
	CANCELLED
}

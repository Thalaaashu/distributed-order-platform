package com.ashish.distributed_order_platform.order.exception;

import java.util.UUID;

import com.ashish.distributed_order_platform.common.exception.ResourceNotFoundException;

public class OrderNotFoundException extends ResourceNotFoundException {

	public OrderNotFoundException(UUID orderId) {
		super("Order " + orderId + " was not found");
	}
}

package com.ashish.distributed_order_platform.order.exception;

import com.ashish.distributed_order_platform.common.exception.BusinessRuleException;

public class InvalidOrderException extends BusinessRuleException {

	public InvalidOrderException(String message) {
		super("INVALID_ORDER", message);
	}
}

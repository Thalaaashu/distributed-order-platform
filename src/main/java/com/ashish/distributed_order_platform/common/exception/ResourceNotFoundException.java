package com.ashish.distributed_order_platform.common.exception;

/** Base type for "the requested resource does not exist" errors; mapped to HTTP 404. */
public abstract class ResourceNotFoundException extends RuntimeException {

	protected ResourceNotFoundException(String message) {
		super(message);
	}
}

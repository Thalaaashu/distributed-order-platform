package com.ashish.distributed_order_platform.common.exception;

/**
 * Base type for syntactically valid requests that violate a domain rule; mapped to HTTP 422.
 * Carries a stable machine-readable code so clients never have to parse messages.
 */
public abstract class BusinessRuleException extends RuntimeException {

	private final String code;

	protected BusinessRuleException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}

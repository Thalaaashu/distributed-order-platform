package com.ashish.distributed_order_platform.order.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.ashish.distributed_order_platform.order.dto.CreateOrderRequest;
import com.ashish.distributed_order_platform.order.dto.OrderResponse;
import com.ashish.distributed_order_platform.order.service.OrderService;

import jakarta.validation.Valid;

/** HTTP adapter only: parse, validate, delegate, choose the status code. No business rules here. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@PostMapping
	public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
		OrderResponse created = orderService.createOrder(request);
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}")
				.buildAndExpand(created.orderId())
				.toUri();
		return ResponseEntity.created(location).body(created);
	}

	@GetMapping("/{orderId}")
	public OrderResponse getOrder(@PathVariable UUID orderId) {
		return orderService.getOrder(orderId);
	}
}

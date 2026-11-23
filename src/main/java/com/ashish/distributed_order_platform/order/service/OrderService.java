package com.ashish.distributed_order_platform.order.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ashish.distributed_order_platform.order.dto.CreateOrderRequest;
import com.ashish.distributed_order_platform.order.dto.OrderResponse;
import com.ashish.distributed_order_platform.order.entity.Order;
import com.ashish.distributed_order_platform.order.entity.OrderItem;
import com.ashish.distributed_order_platform.order.exception.OrderNotFoundException;
import com.ashish.distributed_order_platform.order.repository.OrderRepository;

@Service
public class OrderService {

	private final OrderRepository orderRepository;
	private final Clock clock;

	public OrderService(OrderRepository orderRepository, Clock clock) {
		this.orderRepository = orderRepository;
		this.clock = clock;
	}

	/** One transaction: the order and its items are inserted atomically or not at all. */
	@Transactional
	public OrderResponse createOrder(CreateOrderRequest request) {
		List<OrderItem> items = request.items().stream()
				.map(i -> new OrderItem(i.productId(), i.quantity(), i.unitPrice()))
				.toList();
		// Millisecond precision keeps the value identical before and after a database round trip.
		Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
		Order order = Order.create(request.customerId(), request.currency(), items, now);
		return OrderResponse.from(orderRepository.save(order));
	}

	@Transactional(readOnly = true)
	public OrderResponse getOrder(UUID orderId) {
		return orderRepository.findById(orderId)
				.map(OrderResponse::from)
				.orElseThrow(() -> new OrderNotFoundException(orderId));
	}
}

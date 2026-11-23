package com.ashish.distributed_order_platform.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ashish.distributed_order_platform.order.dto.CreateOrderRequest;
import com.ashish.distributed_order_platform.order.dto.OrderItemRequest;
import com.ashish.distributed_order_platform.order.dto.OrderResponse;
import com.ashish.distributed_order_platform.order.entity.Order;
import com.ashish.distributed_order_platform.order.entity.OrderStatus;
import com.ashish.distributed_order_platform.order.exception.OrderNotFoundException;
import com.ashish.distributed_order_platform.order.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00.123456789Z");

	@Mock
	OrderRepository orderRepository;

	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

	private OrderService service() {
		return new OrderService(orderRepository, clock);
	}

	@Test
	void createOrderPersistsOrderStampedWithClockTruncatedToMillis() {
		when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
			Order order = inv.getArgument(0);
			// A real save() initialises @Version; mimic it so the fixture matches production.
			ReflectionTestUtils.setField(order, "version", 0L);
			return order;
		});
		UUID customer = UUID.randomUUID();
		CreateOrderRequest request = new CreateOrderRequest(customer, "EUR",
				List.of(new OrderItemRequest(UUID.randomUUID(), 2, new BigDecimal("5.50"))));

		OrderResponse response = service().createOrder(request);

		ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(saved.capture());
		assertThat(saved.getValue().getCustomerId()).isEqualTo(customer);
		assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
		assertThat(response.totalAmount()).isEqualByComparingTo("11.00");
		assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-01-01T10:00:00.123Z"));
	}

	@Test
	void getOrderThrowsWhenMissing() {
		UUID id = UUID.randomUUID();
		when(orderRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().getOrder(id)).isInstanceOf(OrderNotFoundException.class);
	}
}

package com.ashish.distributed_order_platform.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ashish.distributed_order_platform.order.exception.InvalidOrderException;

class OrderTest {

	private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
	private static final UUID CUSTOMER = UUID.randomUUID();

	private static OrderItem item(int quantity, String price) {
		return new OrderItem(UUID.randomUUID(), quantity, new BigDecimal(price));
	}

	@Test
	void createsOrderInCreatedStateWithServerComputedTotal() {
		Order order = Order.create(CUSTOMER, "USD", List.of(item(2, "12.99"), item(3, "0.10")), NOW);

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
		assertThat(order.getTotalAmount()).isEqualByComparingTo("26.28");
		assertThat(order.getId()).isNotNull();
		assertThat(order.getCreatedAt()).isEqualTo(NOW);
		assertThat(order.getUpdatedAt()).isEqualTo(NOW);
		assertThat(order.getVersion()).isNull();
	}

	@Test
	void totalIsExactWhereDoubleArithmeticWouldDrift() {
		// 0.10 * 3 == 0.30000000000000004 with double
		Order order = Order.create(CUSTOMER, "USD", List.of(item(3, "0.10")), NOW);

		assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("0.30"));
	}

	@Test
	void wholeNumberPricesAreNormalisedToTwoDecimals() {
		Order order = Order.create(CUSTOMER, "USD", List.of(item(1, "10")), NOW);

		assertThat(order.getItems().getFirst().getUnitPrice()).isEqualTo(new BigDecimal("10.00"));
		assertThat(order.getTotalAmount()).isEqualTo(new BigDecimal("10.00"));
	}

	@Test
	void rejectsEmptyOrder() {
		assertThatThrownBy(() -> Order.create(CUSTOMER, "USD", List.of(), NOW))
				.isInstanceOf(InvalidOrderException.class);
	}

	@Test
	void rejectsUnknownCurrencyCode() {
		assertThatThrownBy(() -> Order.create(CUSTOMER, "ZZZ", List.of(item(1, "1.00")), NOW))
				.isInstanceOf(InvalidOrderException.class)
				.hasMessageContaining("ZZZ");
	}

	@Test
	void rejectsPriceWithMoreThanTwoDecimals() {
		assertThatThrownBy(() -> Order.create(CUSTOMER, "USD", List.of(item(1, "1.005")), NOW))
				.isInstanceOf(InvalidOrderException.class);
	}

	@Test
	void rejectsNonPositiveQuantityAndPrice() {
		assertThatThrownBy(() -> Order.create(CUSTOMER, "USD", List.of(item(0, "1.00")), NOW))
				.isInstanceOf(InvalidOrderException.class);
		assertThatThrownBy(() -> Order.create(CUSTOMER, "USD", List.of(item(1, "0.00")), NOW))
				.isInstanceOf(InvalidOrderException.class);
	}

	@Test
	void itemsListIsNotExternallyMutable() {
		Order order = Order.create(CUSTOMER, "USD", List.of(item(1, "1.00")), NOW);

		assertThatThrownBy(() -> order.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
	}
}

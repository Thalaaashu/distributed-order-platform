package com.ashish.distributed_order_platform.order.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.ashish.distributed_order_platform.order.exception.InvalidOrderException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Aggregate root. All invariants are enforced in {@link #create}, so an invalid Order cannot be built
 * regardless of which caller (REST, a future Kafka consumer, a test) constructs it.
 */
@Entity
@Table(name = "orders") // "order" is a reserved SQL word
public class Order {

	static final int MONEY_SCALE = 2;

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private UUID customerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OrderStatus status;

	@Column(nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal totalAmount;

	@Column(nullable = false, updatable = false, length = 3)
	private String currency;

	@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo ASC")
	private List<OrderItem> items = new ArrayList<>();

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	// null until first persisted; Hibernate then manages it (optimistic locking)
	@Version
	private Long version;

	protected Order() {
		// required by JPA
	}

	public static Order create(UUID customerId, String currency, List<OrderItem> items, Instant now) {
		Objects.requireNonNull(customerId, "customerId");
		Objects.requireNonNull(items, "items");
		Objects.requireNonNull(now, "now");
		if (items.isEmpty()) {
			throw new InvalidOrderException("An order must contain at least one item");
		}
		validateCurrency(currency);
		items.forEach(Order::validateItem);

		Order order = new Order();
		order.id = UUID.randomUUID();
		order.customerId = customerId;
		order.currency = currency;
		order.status = OrderStatus.CREATED;
		order.createdAt = now;
		order.updatedAt = now;
		for (OrderItem item : items) {
			item.attachTo(order, order.items.size());
			order.items.add(item);
		}
		order.totalAmount = items.stream()
				.map(OrderItem::lineTotal)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(MONEY_SCALE);
		return order;
	}

	private static void validateCurrency(String currency) {
		try {
			Currency.getInstance(Objects.requireNonNull(currency, "currency"));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidOrderException("Unsupported currency code: " + currency);
		}
	}

	private static void validateItem(OrderItem item) {
		if (item.getQuantity() < 1) {
			throw new InvalidOrderException("Item quantity must be at least 1");
		}
		if (item.getUnitPrice().signum() <= 0 || item.getUnitPrice().scale() > MONEY_SCALE) {
			throw new InvalidOrderException(
					"Item unit price must be positive with at most " + MONEY_SCALE + " decimal places");
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getCustomerId() {
		return customerId;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public String getCurrency() {
		return currency;
	}

	public List<OrderItem> getItems() {
		return Collections.unmodifiableList(items);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}
}

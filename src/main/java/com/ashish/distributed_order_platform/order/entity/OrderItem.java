package com.ashish.distributed_order_platform.order.entity;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItem {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false, updatable = false)
	private Order order;

	@Column(nullable = false, updatable = false)
	private int lineNo;

	@Column(nullable = false, updatable = false)
	private UUID productId;

	@Column(nullable = false, updatable = false)
	private int quantity;

	@Column(nullable = false, updatable = false, precision = 19, scale = 2)
	private BigDecimal unitPrice;

	protected OrderItem() {
		// required by JPA
	}

	public OrderItem(UUID productId, int quantity, BigDecimal unitPrice) {
		this.id = UUID.randomUUID();
		this.productId = productId;
		this.quantity = quantity;
		// Pad to the stored scale so the in-memory value equals what a later read returns (10 vs 10.00).
		// Scales above the limit are left untouched and rejected by Order.create.
		this.unitPrice = unitPrice.scale() < Order.MONEY_SCALE ? unitPrice.setScale(Order.MONEY_SCALE) : unitPrice;
	}

	BigDecimal lineTotal() {
		return unitPrice.multiply(BigDecimal.valueOf(quantity));
	}

	void attachTo(Order order, int lineNo) {
		this.order = order;
		this.lineNo = lineNo;
	}

	public UUID getId() {
		return id;
	}

	public UUID getProductId() {
		return productId;
	}

	public int getQuantity() {
		return quantity;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}
}

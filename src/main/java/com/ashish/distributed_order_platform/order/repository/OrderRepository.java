package com.ashish.distributed_order_platform.order.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ashish.distributed_order_platform.order.entity.Order;

public interface OrderRepository extends JpaRepository<Order, UUID> {

	/** Fetches items in the same query (one JOIN) instead of a second lazy-load query. */
	@Override
	@EntityGraph(attributePaths = "items")
	Optional<Order> findById(UUID id);
}

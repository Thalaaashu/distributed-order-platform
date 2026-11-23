package com.ashish.distributed_order_platform.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ashish.distributed_order_platform.common.exception.GlobalExceptionHandler;
import com.ashish.distributed_order_platform.order.dto.OrderItemResponse;
import com.ashish.distributed_order_platform.order.dto.OrderResponse;
import com.ashish.distributed_order_platform.order.entity.OrderStatus;
import com.ashish.distributed_order_platform.order.exception.InvalidOrderException;
import com.ashish.distributed_order_platform.order.exception.OrderNotFoundException;
import com.ashish.distributed_order_platform.order.service.OrderService;

@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

	private static final String VALID_BODY = """
			{"customerId":"%s","currency":"USD","items":[{"productId":"%s","quantity":2,"unitPrice":12.99}]}
			""".formatted(UUID.randomUUID(), UUID.randomUUID());

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	OrderService orderService;

	private static OrderResponse sampleResponse(UUID id) {
		return new OrderResponse(id, UUID.randomUUID(), OrderStatus.CREATED, new BigDecimal("25.98"), "USD",
				List.of(new OrderItemResponse(UUID.randomUUID(), 2, new BigDecimal("12.99"))),
				Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T10:00:00Z"), 0);
	}

	@Test
	void createReturns201WithLocationHeader() throws Exception {
		UUID id = UUID.randomUUID();
		when(orderService.createOrder(any())).thenReturn(sampleResponse(id));

		mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "http://localhost/api/v1/orders/" + id))
				.andExpect(jsonPath("$.orderId").value(id.toString()))
				.andExpect(jsonPath("$.totalAmount").value(25.98));
	}

	@Test
	void invalidBodyReturns400WithFieldErrorsAndNeverReachesService() throws Exception {
		String body = """
				{"currency":"usd","items":[{"productId":"%s","quantity":0,"unitPrice":-1}]}
				""".formatted(UUID.randomUUID());

		mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field=='customerId')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='currency')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='items[0].quantity')]").exists())
				.andExpect(jsonPath("$.errors[?(@.field=='items[0].unitPrice')]").exists());
		verifyNoInteractions(orderService);
	}

	@Test
	void emptyItemsReturns400() throws Exception {
		String body = """
				{"customerId":"%s","currency":"USD","items":[]}
				""".formatted(UUID.randomUUID());

		mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='items')]").exists());
	}

	@Test
	void malformedJsonReturns400WithCode() throws Exception {
		mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	@Test
	void businessRuleViolationReturns422() throws Exception {
		when(orderService.createOrder(any())).thenThrow(new InvalidOrderException("Unsupported currency code: ZZZ"));

		mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVALID_ORDER"));
	}

	@Test
	void getReturnsOrder() throws Exception {
		UUID id = UUID.randomUUID();
		when(orderService.getOrder(id)).thenReturn(sampleResponse(id));

		mockMvc.perform(get("/api/v1/orders/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CREATED"))
				.andExpect(jsonPath("$.items[0].quantity").value(2));
	}

	@Test
	void getUnknownOrderReturns404Problem() throws Exception {
		UUID id = UUID.randomUUID();
		when(orderService.getOrder(id)).thenThrow(new OrderNotFoundException(id));

		mockMvc.perform(get("/api/v1/orders/{id}", id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void getWithMalformedUuidReturns400() throws Exception {
		mockMvc.perform(get("/api/v1/orders/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		verifyNoInteractions(orderService);
	}

	@Test
	void unexpectedExceptionReturns500WithoutLeakingDetails() throws Exception {
		UUID id = UUID.randomUUID();
		when(orderService.getOrder(id)).thenThrow(new IllegalStateException("jdbc:h2:mem secret internals"));

		mockMvc.perform(get("/api/v1/orders/{id}", id))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
	}
}

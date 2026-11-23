package com.ashish.distributed_order_platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/** Full stack: HTTP -> validation -> service -> JPA -> H2, with no mocks. */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	private String body(String customerId, String extraField) {
		return """
				{"customerId":"%s","currency":"USD",%s
				 "items":[
				   {"productId":"%s","quantity":3,"unitPrice":0.10},
				   {"productId":"%s","quantity":1,"unitPrice":10}
				 ]}
				""".formatted(customerId, extraField, UUID.randomUUID(), UUID.randomUUID());
	}

	private MvcResult create(String json) throws Exception {
		return mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated())
				.andReturn();
	}

	@Test
	void createdOrderCanBeFetchedAndIsPersistedWithExactMoney() throws Exception {
		String customerId = UUID.randomUUID().toString();
		MvcResult created = create(body(customerId, ""));

		String orderId = JsonPath.read(created.getResponse().getContentAsString(), "$.orderId");
		assertThat(created.getResponse().getHeader("Location")).endsWith("/api/v1/orders/" + orderId);

		mockMvc.perform(get("/api/v1/orders/{id}", orderId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").value(orderId))
				.andExpect(jsonPath("$.customerId").value(customerId))
				.andExpect(jsonPath("$.status").value("CREATED"))
				.andExpect(jsonPath("$.totalAmount").value(10.30))
				.andExpect(jsonPath("$.version").value(0))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].quantity").value(3))
				.andExpect(jsonPath("$.items[1].unitPrice").value(10.00));

		BigDecimal stored = jdbc.queryForObject("select total_amount from orders where id = ?", BigDecimal.class,
				UUID.fromString(orderId));
		assertThat(stored).isEqualTo(new BigDecimal("10.30"));
		Integer itemRows = jdbc.queryForObject("select count(*) from order_items where order_id = ?", Integer.class,
				UUID.fromString(orderId));
		assertThat(itemRows).isEqualTo(2);
	}

	@Test
	void postAndGetReturnIdenticalRepresentation() throws Exception {
		MvcResult created = create(body(UUID.randomUUID().toString(), ""));
		String createdJson = created.getResponse().getContentAsString();
		String orderId = JsonPath.read(createdJson, "$.orderId");

		String fetchedJson = mockMvc.perform(get("/api/v1/orders/{id}", orderId))
				.andReturn().getResponse().getContentAsString();

		assertThat(fetchedJson).isEqualTo(createdJson);
	}

	@Test
	void clientSuppliedTotalIsIgnored() throws Exception {
		MvcResult created = create(body(UUID.randomUUID().toString(), "\"totalAmount\":0.01,"));

		assertThat((Double) JsonPath.read(created.getResponse().getContentAsString(), "$.totalAmount"))
				.isEqualTo(10.30);
	}

	@Test
	void unknownOrderReturns404Problem() throws Exception {
		mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void unsupportedCurrencyReturns422AndPersistsNothing() throws Exception {
		Integer before = jdbc.queryForObject("select count(*) from orders", Integer.class);
		String json = """
				{"customerId":"%s","currency":"ZZZ","items":[{"productId":"%s","quantity":1,"unitPrice":1.00}]}
				""".formatted(UUID.randomUUID(), UUID.randomUUID());

		mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVALID_ORDER"));

		assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(before);
	}
}

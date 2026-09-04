package com.uniblox.store.cart;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.uniblox.store.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CartApiIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void createsAnOpenCart() throws Exception {
        mockMvc.perform(post("/api/carts"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/carts/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void retrievesAnExistingCart() throws Exception {
        String created = mockMvc.perform(post("/api/carts"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cartId = JsonPath.read(created, "$.id");

        mockMvc.perform(get("/api/carts/{cartId}", cartId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cartId))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void returnsNotFoundForAnUnknownCart() throws Exception {
        mockMvc.perform(get("/api/carts/{cartId}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Cart not found"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }
}

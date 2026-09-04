package com.uniblox.store.product;

import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
class ProductController {

    private final ProductRepository products;

    ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    List<ProductResponse> list() {
        return products.findAll(Sort.by("id")).stream().map(ProductResponse::from).toList();
    }
}

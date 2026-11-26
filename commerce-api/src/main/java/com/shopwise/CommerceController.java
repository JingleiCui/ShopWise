package com.shopwise;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class CommerceController {
  private final CommerceService commerce;

  CommerceController(CommerceService commerce) {
    this.commerce = commerce;
  }

  record Quantity(Integer quantity) {}

  @GetMapping("/cart")
  Map<String, Object> cart(Authentication auth) {
    return commerce.cart(auth.getName());
  }

  @PutMapping("/cart/items/{sku}")
  Map<String, Object> item(
      Authentication auth, @PathVariable long sku, @RequestBody Quantity value) {
    return commerce.setQuantity(auth.getName(), sku, value.quantity());
  }

  @PostMapping("/cart/items/{sku}/add")
  Map<String, Object> add(
      Authentication auth,
      @PathVariable long sku,
      @RequestBody Quantity value,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return commerce.addQuantity(auth.getName(), sku, value.quantity(), key);
  }

  @PostMapping("/quotes")
  Map<String, Object> quote(
      Authentication auth, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return commerce.quote(auth.getName(), key);
  }

  @GetMapping("/quotes/{id}")
  Map<String, Object> quote(Authentication auth, @PathVariable String id) {
    return commerce.getQuote(auth.getName(), id);
  }

  @PostMapping("/quotes/{id}/confirm")
  Map<String, Object> confirm(Authentication auth, @PathVariable String id) {
    return commerce.confirm(auth.getName(), id);
  }

  @GetMapping("/orders")
  List<Map<String, Object>> orders(Authentication auth) {
    return commerce.orders(auth.getName(), SessionController.staff(auth));
  }

  @GetMapping("/orders/{id}")
  Map<String, Object> order(Authentication auth, @PathVariable String id) {
    return commerce.order(auth.getName(), SessionController.staff(auth), id);
  }

  @PostMapping("/orders/{id}/{action:pay|ship|receive|cancel}")
  Map<String, Object> action(
      Authentication auth, @PathVariable String id, @PathVariable String action) {
    return commerce.act(auth.getName(), SessionController.staff(auth), id, action);
  }

  @PostMapping("/orders/{id}/returns")
  Map<String, Object> requestReturn(
      Authentication auth,
      @PathVariable String id,
      @RequestBody CommerceService.ReturnRequest input,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return commerce.requestReturn(auth.getName(), id, input, key);
  }

  @GetMapping("/returns")
  List<Map<String, Object>> returns(Authentication auth) {
    return commerce.returns(auth.getName(), SessionController.staff(auth));
  }

  @PostMapping("/returns/{id}/{action:approve|reject|refund}")
  Map<String, Object> returnAction(@PathVariable String id, @PathVariable String action) {
    return commerce.returnAction(id, action);
  }
}

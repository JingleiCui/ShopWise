package com.shopwise;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api")
class CatalogController {
  private final JdbcTemplate db;
  private final ObjectMapper json;

  CatalogController(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  @GetMapping("/products")
  List<Map<String, Object>> products(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String category,
      @RequestParam(required = false) BigDecimal maxPrice,
      @RequestParam(defaultValue = "false") boolean inStock) {
    return search(q, category, maxPrice, inStock, null);
  }

  @GetMapping("/products/{id}")
  Map<String, Object> product(@PathVariable long id) {
    return search("", "", null, false, id).stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在"));
  }

  private List<Map<String, Object>> search(
      String q, String category, BigDecimal maxPrice, boolean inStock, Long id) {
    var rows =
        db.queryForList(
            """
select p.id,p.id as "sourceId",p.source_url as "sourceUrl",p.source_title as "sourceTitle",p.title,p.category,p.description,p.kind,p.image,p.source,p.version,p.source_image as "sourceImage",
       p.attributes::text as attributes,s.id as sku,s.label,s.price,s.stock
from products p join skus s on s.product_id=p.id
where (?='' or p.category=?) and (cast(? as numeric) is null or s.price<=?) and (not ? or s.stock>0)
  and (cast(? as bigint) is null or p.id=?)
  and position(lower(?) in lower(concat_ws(' ',p.title,p.category,p.description,p.kind,p.attributes::text,s.label)))>0
order by p.id,s.id
""",
            category,
            category,
            maxPrice,
            maxPrice,
            inStock,
            id,
            id,
            q.strip());
    Map<Long, Map<String, Object>> products = new LinkedHashMap<>();
    Map<Long, List<Object>> skus = new HashMap<>();
    for (var row : rows) {
      long pid = ((Number) row.get("id")).longValue();
      var sku =
          Map.of(
              "id",
              row.remove("sku"),
              "label",
              row.remove("label"),
              "price",
              row.remove("price"),
              "stock",
              row.remove("stock"));
      var variants = skus.computeIfAbsent(pid, k -> new ArrayList<>());
      variants.add(sku);
      if (!products.containsKey(pid)) {
        row.put("attributes", json.readTree((String) row.get("attributes")));
        row.put("skus", variants);
        products.put(pid, row);
      }
    }
    return List.copyOf(products.values());
  }

  record Edit(Integer stock, BigDecimal price) {}

  @PatchMapping("/staff/skus/{id}")
  Map<String, Object> edit(@PathVariable long id, @RequestBody Edit edit) {
    if ((edit.stock == null && edit.price == null)
        || (edit.stock != null && edit.stock < 0)
        || (edit.price != null
            && (edit.price.signum() <= 0
                || edit.price.scale() > 2
                || edit.price.precision() - edit.price.scale() > 10)))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "库存或价格无效");
    var changed =
        db.queryForList(
            """
update skus set stock=coalesce(?,stock),price=coalesce(?,price) where id=? returning id,label,price,stock
""",
            edit.stock,
            edit.price,
            id);
    return changed.stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规格不存在"));
  }
}

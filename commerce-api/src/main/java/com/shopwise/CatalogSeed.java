package com.shopwise;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
@org.springframework.context.annotation.Profile("!prod")
public class CatalogSeed implements ApplicationRunner {
  record Sku(String label, BigDecimal price, int stock) {}

  record Product(
      long sourceId,
      String source,
      String version,
      String sourceUrl,
      String sourceTitle,
      String title,
      String description,
      String category,
      String kind,
      String image,
      String sourceImage,
      Map<String, String> attributes,
      List<Sku> skus) {}

  private final JdbcTemplate db;
  private final ObjectMapper json;

  CatalogSeed(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception {
    try (var input = new ClassPathResource("catalog.json").getInputStream()) {
      for (var p : json.readValue(input, new TypeReference<List<Product>>() {})) {
        db.update(
            """
insert into products(id,source,version,title,description,category,kind,image,source_image,attributes,source_url,source_title)
values(?,?,?,?,?,?,?,?,?,cast(? as jsonb),?,?)
on conflict(id) do update set source_url=excluded.source_url,source_title=excluded.source_title
where products.source_url is distinct from excluded.source_url or products.source_title is distinct from excluded.source_title
""",
            p.sourceId,
            p.source,
            p.version,
            p.title,
            p.description,
            p.category,
            p.kind,
            p.image,
            p.sourceImage,
            json.writeValueAsString(p.attributes),
            p.sourceUrl,
            p.sourceTitle);
        for (var s : p.skus)
          db.update(
              "insert into skus(product_id,label,price,stock) values(?,?,?,?) on"
                  + " conflict(product_id,label) do nothing",
              p.sourceId,
              s.label,
              s.price,
              s.stock);
      }
    }
  }
}

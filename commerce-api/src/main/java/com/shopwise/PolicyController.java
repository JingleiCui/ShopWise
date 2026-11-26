package com.shopwise;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class PolicyController {
  private final JdbcTemplate db;
  private final boolean vector;

  PolicyController(JdbcTemplate db, @Value("${POLICY_VECTOR:false}") boolean vector) {
    this.db = db;
    this.vector = vector;
    if (vector) db.execute("create extension if not exists vector");
  }

  private static SortedSet<String> tokens(String text) {
    var tokens = new TreeSet<String>();
    for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
      if (word.isBlank()) continue;
      tokens.add(word);
      if (word.codePoints()
          .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))
        for (int i = 1; i < word.length(); i++) tokens.add(word.substring(i - 1, i + 1));
    }
    return tokens;
  }

  private static String embedding(Set<String> words) {
    int[] counts = new int[256];
    for (var word : words) counts[Math.floorMod(word.hashCode(), counts.length)]++;
    return Arrays.toString(counts);
  }

  @GetMapping("/api/policies")
  List<Map<String, Object>> policies(@RequestParam(defaultValue = "") String q) {
    var rows =
        db.queryForList(
            "select id,title,content,source,version,keywords from policies order by id");
    var query = tokens(q);
    Map<Object, Double> scores = new HashMap<>();
    for (var row : rows) {
      var words =
          tokens(row.get("title") + " " + row.remove("keywords") + " " + row.get("content"));
      double score = query.stream().filter(words::contains).count();
      if (vector && !query.isEmpty() && score > 0) {
        // The optional vector extension compares local token hashes without sending policy text
        // outside the store.
        score +=
            db.queryForObject(
                "select 1-(cast(? as vector) <=> cast(? as vector))",
                Double.class,
                embedding(query),
                embedding(words));
      }
      scores.put(row.get("id"), score);
    }
    return rows.stream()
        .filter(r -> query.isEmpty() || scores.get(r.get("id")) > 0)
        .sorted(
            Comparator.comparingDouble((Map<String, Object> r) -> scores.get(r.get("id")))
                .reversed())
        .toList();
  }
}

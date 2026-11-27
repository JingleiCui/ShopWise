package com.shopwise.discovery;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

final class ProductMatch {
    private static final Pattern BUNDLE=Pattern.compile("(?i)\\bbundle\\b|\\b(?:pack of|set of)\\s*\\d|\\b\\d+\\s*[- ]?(?:pack|pcs|pieces|count)\\b|套装|组合|\\d+件装");
    private static final Pattern VARIANT=Pattern.compile("(?i)\\b(\\d+(?:\\.\\d+)?\\s*(?:tb|gb|mah|ml|mm|cm))\\b");

    record Match(String evidence,List<String> offers) {}

    static List<Match> crossPlatform(List<Offer> offers) {
        List<List<Offer>> groups=new ArrayList<>();
        for (var offer:offers) {
            var group=groups.stream().filter(g->g.stream().allMatch(o->evidence(o,offer)!=null)).findFirst();
            if (group.isPresent()) group.get().add(offer);
            else groups.add(new ArrayList<>(List.of(offer)));
        }
        return groups.stream().filter(g->g.stream().map(Offer::provider).distinct().count()>1)
            .map(g->new Match(g.stream().allMatch(o->gtin(o.gtin())!=null)?"GTIN":"BRAND_MPN",
                g.stream().map(o->o.provider()+"/"+o.variantId()).distinct().toList())).toList();
    }

    static String evidence(Offer left,Offer right) {
        if (!Objects.equals(left.destination(),right.destination()) || !newItem(left.condition()) || !newItem(right.condition())) return null;
        if (BUNDLE.matcher(left.title()).find() || BUNDLE.matcher(right.title()).find()) return null;
        if (left.attributes().entrySet().stream().anyMatch(e->right.attributes().containsKey(e.getKey())
            && !e.getValue().equals(right.attributes().get(e.getKey())))) return null;
        if (left.attributes().containsValue("?") || right.attributes().containsValue("?")) return null;
        if ((left.attributes().containsKey("pack") || right.attributes().containsKey("pack"))
            && !Objects.equals(left.attributes().get("pack"),right.attributes().get("pack"))) return null;
        var leftSpecs=specs(left);
        var rightSpecs=specs(right);
        if (!leftSpecs.isEmpty() && !rightSpecs.isEmpty() && !leftSpecs.equals(rightSpecs)) return null;
        if (left.brand()!=null && right.brand()!=null && !left.brand().equals(right.brand())) return null;
        if (left.mpn()!=null && right.mpn()!=null && !left.mpn().equals(right.mpn())) return null;
        String a=gtin(left.gtin()), b=gtin(right.gtin());
        if (a!=null && b!=null) return a.equals(b)?"GTIN":null;
        if (left.brand()==null || left.mpn()==null || !left.brand().equals(right.brand()) || !left.mpn().equals(right.mpn())) return null;
        return !left.attributes().isEmpty() && left.attributes().equals(right.attributes())?"BRAND_MPN":null;
    }

    private static Set<String> specs(Offer offer) {
        var values=new TreeSet<String>();
        VARIANT.matcher(offer.title()+" "+offer.variant()).results().forEach(m->values.add(m.group(1).replaceAll("\\s","").toLowerCase(Locale.ROOT)));
        return values;
    }

    static boolean newItem(String condition) { return Set.of("new","1000").contains(normalize(condition)); }

    static String normalize(String value) {
        return value==null?"":Normalizer.normalize(value,Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
    }

    static String identifier(String value) {
        String normalized=normalize(value);
        return normalized.length()>1 && !Set.of("unknown","does not apply","n/a","na","unbranded","generic").contains(normalized)?normalized:null;
    }

    static String gtin(String value) {
        String digits=normalize(value).replaceAll("[ -]","");
        if (!digits.matches("(?:[0-9]{8}|[0-9]{12,14})") || digits.matches("(.)\\1+")) return null;
        int total=0, multiplier=3;
        for (int i=digits.length()-2;i>=0;i--) {
            total+=(digits.charAt(i)-'0')*multiplier;
            multiplier=4-multiplier;
        }
        return (10-total%10)%10==digits.charAt(digits.length()-1)-'0'?"0".repeat(14-digits.length())+digits:null;
    }

    static void attribute(Map<String,String> attrs,String rawKey,String rawValue) {
        String value=normalize(rawValue);
        String key=switch(normalize(rawKey)) {
            case "colour","颜色" -> "color";
            case "storage capacity","capacity","存储容量" -> "storage";
            case "尺寸","尺码" -> "size";
            case "pack quantity","number of items","unit quantity","包装数量" -> "pack";
            case "color","storage","size","pack","region","voltage","connectivity","edition" -> normalize(rawKey);
            default -> "";
        };
        if (!key.isEmpty() && !value.isEmpty()) attrs.merge(key,value,(a,b)->a.equals(b)?a:"?");
    }
}

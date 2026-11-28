package com.shopwise.discovery;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/discovery")
class DiscoveryController {
    record Source(String provider, String status, List<Offer> offers) {}
    record Batch(String destination, String shippingStatus, String taxStatus, List<Source> sources, List<ProductMatch.Match> matches) {}
    private final Map<String,CatalogProvider> providers=new LinkedHashMap<>();
    private final Map<String,Gate> gates=new HashMap<>();
    private final ScheduledExecutorService pool=Executors.newScheduledThreadPool(8);

    private static final Pattern PRIVATE_QUERY=Pattern.compile(
        "[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|https?://|(?:\\d[\\s+-]*){7,}|手机号|身份证|详细地址|门牌|(?:路|街道|巷)\\s*\\d+\\s*号",
        Pattern.CASE_INSENSITIVE);

    private final ReferenceRates rates;
    DiscoveryController(List<CatalogProvider> sources,ReferenceRates rates) {
        this.rates=rates;
        for (var source : sources) { providers.put(source.name(),source); gates.put(source.name(),new Gate()); }
    }
    @PreDestroy void stop() { pool.shutdownNow(); }

    // Reserve short queue slots so a normal burst stays within the upstream quota.
    static class Gate {
        private static final long INTERVAL=TimeUnit.MILLISECONDS.toNanos(250);
        private static final long MAX_WAIT=TimeUnit.SECONDS.toNanos(5);
        private long next=System.nanoTime(), reopen=next;
        private int failures;

        synchronized long reserve() {
            long now=System.nanoTime();
            long delay=Math.max(0,next-now);
            if (now<reopen || delay>MAX_WAIT) return -1;
            next=now+delay+INTERVAL;
            return delay;
        }
        synchronized boolean open() { return System.nanoTime()>=reopen; }
        synchronized void finish(boolean ok) {
            if (ok) { failures=0; return; }
            if (++failures>=3) { reopen=System.nanoTime()+15_000_000_000L; failures=0; }
        }
    }

    @GetMapping
    Batch search(@RequestParam String q,@RequestParam(required=false) BigDecimal budget,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        String query=q.strip();
        if (query.isEmpty() || query.length()>120 || budget!=null && (budget.signum()<0 || budget.compareTo(BigDecimal.valueOf(1e8))>0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请核对关键词和预算");
        if (PRIVATE_QUERY.matcher(query).find() && ProductMatch.gtin(query)==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入商品名称、型号或条码");
        var tasks=new ArrayList<CompletableFuture<Source>>();
        for (var p : providers.values()) tasks.add(submit(p,()->{
            var offers=p.search(query);
            return offers.stream().map(o->o.withReference(rates.cny(o))).filter(o -> {
                if (budget==null) return true;
                var amount="CNY".equals(o.currency())?o.price():(o.referenceCny()==null?null:o.referenceCny().amount());
                return amount==null || amount.compareTo(budget)<=0;
            }).toList();
        }));
        var sources=tasks.stream().map(CompletableFuture::join).toList();
        return new Batch("CN","UNKNOWN","UNKNOWN",sources,ProductMatch.crossPlatform(
            sources.stream().flatMap(s->s.offers().stream()).toList()));
    }

    @GetMapping("/shopify/compare")
    Source compareShopify(@RequestParam("id") List<String> ids, HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var provider=providers.get("shopify");
        if (provider==null || ids.size()<2 || ids.size()>4 || Set.copyOf(ids).size()!=ids.size()
            || ids.stream().anyMatch(id->id.isBlank() || id.length()>220))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择 2 至 4 个不同的商品规格");
        return submit(provider,()->provider.lookupAll(ids).stream()
            .filter(o->ids.contains(o.variantId())).map(o->o.withReference(rates.cny(o))).toList()).join();
    }

    @GetMapping("/{provider}/{operation:lookup|details}")
    Source resolve(@PathVariable String provider,@PathVariable String operation,@RequestParam String id,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var p=providers.get(provider);
        if (p==null || id.isBlank() || id.length()>220) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"无效的商品引用");
        return submit(p,()->{
            var offers=operation.equals("lookup")?p.lookup(id):p.details(id);
            return offers.stream().filter(o->o.variantId().equals(id)).map(o->o.withReference(rates.cny(o))).toList();
        }).join();
    }

    @FunctionalInterface interface Read { List<Offer> run() throws Exception; }
    private CompletableFuture<Source> submit(CatalogProvider provider,Read read) {
        String name=provider.name();
        if (!provider.enabled()) return CompletableFuture.completedFuture(new Source(name,"disabled",List.of()));
        Gate gate=gates.get(name);
        long delay=gate.reserve();
        if (delay<0) return CompletableFuture.completedFuture(new Source(name,"busy",List.of()));
        Supplier<Source> work=()->{
            if (!gate.open()) return new Source(name,"busy",List.of());
            try {
                var offers=read.run(); gate.finish(true);
                return new Source(name,offers.isEmpty()?"empty":"ok",offers);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                gate.finish(false);
                return new Source(name,e instanceof java.net.http.HttpTimeoutException?"timeout":"unavailable",List.of());
            }
        };
        var result=new CompletableFuture<Source>();
        try {
            var scheduled=pool.schedule(()->{
                if (!result.isDone()) result.complete(work.get());
            },delay,TimeUnit.NANOSECONDS);
            var timeout=new Source(name,"timeout",List.of());
            result.completeOnTimeout(timeout,8,TimeUnit.SECONDS).whenComplete((value,error)->{
                if (value==timeout) scheduled.cancel(true);
            });
            return result;
        } catch (RejectedExecutionException busy) {
            return CompletableFuture.completedFuture(new Source(name,"busy",List.of()));
        }
    }
}

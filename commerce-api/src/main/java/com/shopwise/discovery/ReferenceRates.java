package com.shopwise.discovery;

import java.io.IOException;
import java.io.StringReader;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ReferenceRates {
    record Reference(BigDecimal amount,LocalDate date) {}
    private record Rates(LocalDate date,Map<String,BigDecimal> values) {}
    private volatile Rates rates;
    private final Path cacheFile;
    private final Path bootstrapFile;
    private final EcbSource source;
    private Instant lastFetch=Instant.EPOCH;
    private static final URI ENDPOINT=URI.create("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml");
    private static final int MAX_XML_BYTES=50_000;

    @FunctionalInterface
    interface EcbSource {
        String download() throws IOException,InterruptedException;
    }

    @org.springframework.beans.factory.annotation.Autowired
    ReferenceRates(@Value("${SHOPWISE_ECB_RATES_CACHE:}") String cacheFile,
                   @Value("${SHOPWISE_ECB_RATES_FILE:}") String bootstrapFile) {
        this(cacheFile.isBlank()?null:Path.of(cacheFile),bootstrapFile.isBlank()?null:Path.of(bootstrapFile),httpSource());
    }

    ReferenceRates(Path cacheFile,Path bootstrapFile,EcbSource source) {
        this.cacheFile=cacheFile;
        this.bootstrapFile=bootstrapFile;
        this.source=source;
    }

    private static EcbSource httpSource() {
        var http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
        return ()->{
            var request=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(8)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode()!=200) throw new IOException("ECB HTTP "+response.statusCode());
            return response.body();
        };
    }

    @Scheduled(initialDelay=1000,fixedDelay=60_000)
    void refresh() {
        if (rates==null) {
            rates=readLocal(cacheFile);
            if (rates==null) rates=readLocal(bootstrapFile);
        }
        if (Instant.now().isBefore(lastFetch.plusSeconds(3600))) return;
        try {
            String body=source.download();
            var updated=parse(body);
            if (updated==null || (rates!=null && updated.date().isBefore(rates.date()))) return;
            rates=updated;
            lastFetch=Instant.now();
            if (cacheFile!=null) saveCache(body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException | XMLStreamException unavailable) {
            // Keep the previous valid rates when the source or local storage is unavailable.
        }
    }

    private static Rates readLocal(Path file) {
        if (file==null) return null;
        try (var stream=Files.newInputStream(file)) {
            byte[] body=stream.readNBytes(MAX_XML_BYTES+1);
            return body.length>MAX_XML_BYTES?null:parse(new String(body,StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException | XMLStreamException unavailable) {
            return null;
        }
    }

    private void saveCache(String body) throws IOException {
        Path target=cacheFile.toAbsolutePath();
        Path temporary=Files.createTempFile(target.getParent(),".ecb-rates-",".xml");
        try {
            Files.writeString(temporary,body,StandardCharsets.UTF_8);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Rates parse(String body) throws XMLStreamException {
        if (body.getBytes(StandardCharsets.UTF_8).length>MAX_XML_BYTES) return null;
        var factory=XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        var xml=factory.createXMLStreamReader(new StringReader(body));
        var values=new HashMap<String,BigDecimal>();
        values.put("EUR",BigDecimal.ONE);
        LocalDate date=null;
        try {
            while (xml.hasNext()) {
                if (xml.next()!=XMLStreamConstants.START_ELEMENT || !xml.getLocalName().equals("Cube")) continue;
                var time=xml.getAttributeValue(null,"time");
                var currency=xml.getAttributeValue(null,"currency");
                if (time!=null) date=LocalDate.parse(time);
                if (currency!=null) {
                    var rate=new BigDecimal(xml.getAttributeValue(null,"rate"));
                    if (rate.signum()>0) values.put(currency,rate);
                }
            }
        } finally { xml.close(); }
        return date!=null && values.containsKey("CNY") && recent(date)
            ? new Rates(date,Map.copyOf(values)) : null;
    }

    private static boolean recent(LocalDate date) {
        LocalDate today=LocalDate.now(ZoneOffset.UTC);
        return !date.isAfter(today) && !date.isBefore(today.minusDays(5));
    }
    Reference cny(Offer offer) {
        var snapshot=rates;
        BigDecimal amount=offer.price();
        if (snapshot==null || amount==null || !recent(snapshot.date())) return null;
        var rate=snapshot.values().get(offer.currency());
        if (rate==null || "CNY".equals(offer.currency())) return null;
        return new Reference(amount.multiply(snapshot.values().get("CNY")).divide(rate,2,RoundingMode.HALF_UP),snapshot.date());
    }
}

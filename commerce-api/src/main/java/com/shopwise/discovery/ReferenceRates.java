package com.shopwise.discovery;

import java.io.StringReader;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import javax.xml.stream.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ReferenceRates {
    record Reference(BigDecimal amount,LocalDate date) {}
    private record Rates(LocalDate date,Map<String,BigDecimal> values) {}
    private volatile Rates rates;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final URI ENDPOINT=URI.create("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml");

    @Scheduled(initialDelay=1000,fixedDelay=3_600_000)
    void refresh() {
        try {
            var response=http.send(HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200 || response.body().length()>50_000) return;
            var factory=XMLInputFactory.newDefaultFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
            var xml=factory.createXMLStreamReader(new StringReader(response.body()));
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
            if (date!=null && values.containsKey("CNY")) rates=new Rates(date,Map.copyOf(values));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.io.IOException | RuntimeException | XMLStreamException unavailable) {
            // Stale rates expire below. Catalog lookup continues with the merchant's original currency.
        }
    }
    Reference cny(Offer offer) {
        var snapshot=rates;
        BigDecimal amount=offer.price();
        if (snapshot==null || amount==null || LocalDate.now(ZoneOffset.UTC).minusDays(5).isAfter(snapshot.date())) return null;
        var rate=snapshot.values().get(offer.currency());
        if (rate==null || "CNY".equals(offer.currency())) return null;
        return new Reference(amount.multiply(snapshot.values().get("CNY")).divide(rate,2,RoundingMode.HALF_UP),snapshot.date());
    }
}

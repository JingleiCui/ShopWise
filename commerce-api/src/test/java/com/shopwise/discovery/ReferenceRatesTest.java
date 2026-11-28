package com.shopwise.discovery;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceRatesTest {
    @TempDir Path directory;

    @Test
    void savesLiveRatesThenUsesThemAfterRestartWhenDownloadFails() throws IOException {
        Path cache=directory.resolve("ecb-rates.xml");
        LocalDate day=LocalDate.now(ZoneOffset.UTC);
        String live=xml(day);
        var initial=new ReferenceRates(cache,null,()->live);
        initial.refresh();
        assertEquals(live,Files.readString(cache));
        assertEquals(new BigDecimal("160.00"),initial.cny(offer()).amount());

        var restarted=new ReferenceRates(cache,null,()->{ throw new IOException("network unavailable"); });
        restarted.refresh();
        assertEquals(day,restarted.cny(offer()).date());
        assertEquals(new BigDecimal("160.00"),restarted.cny(offer()).amount());
        assertEquals(live,Files.readString(cache));
    }

    @Test
    void rejectsExpiredRatesAndKeepsValidCacheWhenUpstreamReturnsInvalidXml() throws IOException {
        Path cache=directory.resolve("ecb-rates.xml");
        LocalDate day=LocalDate.now(ZoneOffset.UTC);
        Files.writeString(cache,xml(day.minusDays(6)));
        var offline=new ReferenceRates(cache,null,()->{ throw new IOException("network unavailable"); });
        offline.refresh();
        assertNull(offline.cny(offer()));

        String valid=xml(day);
        Files.writeString(cache,valid);
        var invalid=new ReferenceRates(cache,null,()->"<invalid");
        invalid.refresh();
        assertEquals(new BigDecimal("160.00"),invalid.cny(offer()).amount());
        assertEquals(valid,Files.readString(cache));
    }

    private static String xml(LocalDate day) {
        return "<Envelope><Cube time='%s'><Cube currency='CNY' rate='8'/></Cube></Envelope>".formatted(day);
    }

    private static Offer offer() {
        return new Offer("shopify","product","variant","Lamp","black","Store","https://example.com",
            "EUR",2000L,"CN",true,Instant.now(),"new",null,null,null,null,Map.of());
    }
}
